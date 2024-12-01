import com.dampcake.bencode.Bencode;
import com.dampcake.bencode.Type;
import org.apache.commons.lang3.tuple.Pair;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class TorrentDownloader {

    private static final int PORT = 6881;

    private static final byte UNCHOKE_MESSAGE_ID = 1;
    private static final byte INTERESTED_MESSAGE_ID = 2;
    private static final byte BITFIELD_MESSAGE_ID = 5;
    private static final byte REQUEST_MESSAGE_ID = 6;
    private static final byte PIECE_MESSAGE_ID = 7;
    private static final int BLOCK_SIZE = 16384;

    private static Queue<Integer> pieceQueue = new ConcurrentLinkedQueue<>();
    private static Map<Integer, byte[]> bufferMap = new ConcurrentHashMap<>();
    private static Lock bufferLock = new ReentrantLock();

    public static byte[] downloadPieceFromPeer(Torrent torrent, String peer, int index) {
        try (Socket socket = new Socket(peer.split(":")[0], Integer.parseInt(peer.split(":")[1]))) {
            TCPService tcpService = new TCPService(socket);
            performHandshake(torrent.getInfoHash(), tcpService, false);
            int pieceLength = (int) torrent.getPieceLength(index);
            return downloadPieceHelper(pieceLength, tcpService, index);
        } catch (Exception e) {
            throw new RuntimeException("Error downloading piece from peer: " + e.getMessage());
        }
    }
    public static byte[] downloadPiece(Torrent torrent, int index) {
        List<String> peerList = null;
        try {
            peerList = getPeerList(torrent);
        } catch (Exception e) {
            throw new RuntimeException("Error getting peer list: " + e.getMessage());
        }

        if (peerList == null || peerList.size() == 0) {
            throw new RuntimeException("No peers available to download from");
        }
        byte piece[] = null;
        for (String peer : peerList) {
            try {
                piece = downloadPieceFromPeer(torrent, peer, index);
                break;
            } catch (Exception e) {
                System.out.println("Error downloading piece from peer: " + peer + ", " + e.getMessage());
            }
        }
        if (piece == null) {
            throw new RuntimeException("Failed to download piece: " + index);
        }
        if (!validatePieceHash(torrent.getPieces().get(index), piece)) {
            throw new RuntimeException("Piece hash validation failed: " + index);
        }
        return piece;
    }

    private static boolean validatePieceHash(String expectedPieceHash, byte[] piece) {
        String actualPieceHash = Utils.calculateSHA1(piece);
        if (!expectedPieceHash.equals(actualPieceHash)) {
            System.out.println("Hash validation failed. Expected hash: " + expectedPieceHash + ", Actual hash: " + actualPieceHash);
        }
        return expectedPieceHash.equals(actualPieceHash);
    }

    private static byte[] downloadPieceHelper(int pieceLength, TCPService tcpService, int index) throws Exception {
        byte[] bitfieldMessage = tcpService.waitForMessage();
        if (bitfieldMessage[0] != BITFIELD_MESSAGE_ID) {
            throw new RuntimeException("Expected bitfield message (5) from peer, but received different message: " + bitfieldMessage[0]);
        }

        // send an interested message to the peer
        byte[] interestedMessage = new byte[]{0, 0, 0, 1, INTERESTED_MESSAGE_ID};
        tcpService.sendMessage(interestedMessage);
        byte[] unchokeMessage = tcpService.waitForMessage();
        if (unchokeMessage[0] != UNCHOKE_MESSAGE_ID) {
            throw new RuntimeException("Expected unchoke message (1) from peer, but received different message: " + unchokeMessage[0]);
        }

        int blocks = (int) Math.ceil((double) pieceLength / BLOCK_SIZE);
        int offset = 0;
        byte[] piece = new byte[pieceLength];
        for (int blockIndex = 0; blockIndex < blocks; blockIndex++) {
            int blockLength = Math.min(BLOCK_SIZE, pieceLength - offset);
            byte[] requestPayload = TCPService.createRequestPayload(index, offset, blockLength);
            tcpService.sendMessage(REQUEST_MESSAGE_ID, requestPayload);
            byte[] pieceMessage = tcpService.waitForMessage();
            if (pieceMessage[0] != PIECE_MESSAGE_ID) {
                throw new RuntimeException("Expected piece message (7) from peer, but received different message: " + pieceMessage[0]);
            }

            System.arraycopy(pieceMessage, 9, piece, offset, blockLength);
            offset += blockLength;
        }
        return piece;
    }

    static Torrent getTorrentFromPath(String torrentFilePath) {
        byte[] torrentFileBytes = Utils.readTorrentFile(torrentFilePath);
        Torrent torrent = new Torrent(torrentFileBytes);
        return torrent;
    }

    static List<String> getPeerList(Torrent torrent) throws URISyntaxException, IOException, InterruptedException {
        String url = torrent.getTrackerURL();
        String infoHash = new String(Utils.hexStringToByteArray(torrent.getInfoHash()),
                StandardCharsets.ISO_8859_1);
        Random random = new Random();
        byte[] peerIdBytes = new byte[10];
        random.nextBytes(peerIdBytes);
        String peerId = Utils.byteToHexString(peerIdBytes);
        int uploaded = 0;
        int downloaded = 0;
        long left = torrent.getLength();
        int compact = 1;

        HttpClient client = HttpClient.newHttpClient();
        String requestURL = String.format("%s?info_hash=%s&peer_id=%s&port=%d&uploaded=%d&downloaded=%d&left=%d&compact=%d",
                url,
                URLEncoder.encode(infoHash, StandardCharsets.ISO_8859_1),
                peerId,
                PORT,
                uploaded,
                downloaded,
                left,
                compact);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI(requestURL))
                .GET()
                .build();

        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

        List<String> peerList = getPeerListFromHTTPResponse(response);
        return peerList;
    }

    private static List<String> getPeerListFromHTTPResponse(HttpResponse<byte[]> response) {
        Bencode bencode = new Bencode(true);
        Map<String, Object> decodedResponse = bencode.decode(response.body(), Type.DICTIONARY);
        byte[] peersBytes = ((ByteBuffer) decodedResponse.get("peers")).array();

        List<String> peerList = new ArrayList<>();
        for (int i = 0; i < peersBytes.length; i += 6) {
            String ip = String.format("%d.%d.%d.%d", peersBytes[i] & 0xff, peersBytes[i + 1] & 0xff,
                    peersBytes[i + 2] & 0xff, peersBytes[i + 3] & 0xff);
            int port = ((peersBytes[i + 4] & 0xff) << 8) | (peersBytes[i + 5] & 0xff);
            peerList.add(ip + ":" + port);
        }
        return peerList;
    }

    private static void validateHandshakeResponse(byte[] response,
                                                  byte[] expectedInfoHash, boolean isMagnetHandshake) {
        if (response[0] != 19) {
            throw new RuntimeException("Invalid protocol length: " + response[0]);
        }
        byte[] protocolBytes = Arrays.copyOfRange(response, 1, 20);
        String protocol = new String(protocolBytes, StandardCharsets.ISO_8859_1);
        if (!"BitTorrent protocol".equals(protocol)) {
            throw new RuntimeException("Invalid protocol: " + protocol);
        }
        if (isMagnetHandshake) {
            if (response[25] != 16) {
                throw new RuntimeException("Invalid reserved byte: " + response[25]);
            }
        }
        byte[] receivedInfoHash = Arrays.copyOfRange(response, 28, 48);
        if (!Arrays.equals(expectedInfoHash, receivedInfoHash)) {
            throw new RuntimeException("Info hash mismatch");
        }
    }


    static void performHandshake(String infoHash, TCPService tcpService, boolean isMagnetHandshake) {
        byte[] handshakeMessage = createHandshakeMessage(infoHash, isMagnetHandshake);
        tcpService.sendMessage(handshakeMessage);
        byte[] handshakeResponse = tcpService.waitForHandshakeResponse();
        validateHandshakeResponse(handshakeResponse, Utils.hexStringToByteArray(infoHash), isMagnetHandshake);
        byte[] peerIdBytes = Arrays.copyOfRange(handshakeResponse, handshakeResponse.length - 20, handshakeResponse.length);
        String peerId = Utils.byteToHexString(peerIdBytes);
        System.out.println("Peer ID: " + peerId);
    }

    static byte[] createHandshakeMessage(String infoHash, boolean isMagnetHandshake) {
        // create a handshake message to send to the peer
        ByteArrayOutputStream handshakeMessage = new ByteArrayOutputStream();
        try {
            handshakeMessage.write(19);
            handshakeMessage.write("BitTorrent protocol".getBytes());
            byte[] reservedBytes = new byte[] {0,0,0,0,0,0,0,0};
            if (isMagnetHandshake) {
                reservedBytes[5] = 16;
            }
            System.out.println("Reserved bytes: " + reservedBytes[5]);
            handshakeMessage.write(reservedBytes);
            System.out.println("Info hash: " + infoHash);
            handshakeMessage.write(Utils.hexStringToByteArray(infoHash));
            handshakeMessage.write("ABCDEFGHIJKLMNOPQRST".getBytes());
            byte[] handshakeMessageBytes = handshakeMessage.toByteArray();
            System.out.println("Handshake message: " + handshakeMessageBytes);
            return handshakeMessageBytes;
        } catch (Exception e) {
            throw new RuntimeException("Error creating handshake message: " + e.getMessage());
        }
    }

    public static void downloadTorrent(Torrent torrent, String storageFilePath) {
        int numPieces = torrent.getPieces().size();

        // create a queue of pieces to download
        // add all the pieces to the queue
        for (int i = 0; i < numPieces; i++) {
            pieceQueue.add(i);
        }
        // create a connection pool to each peer
        List<String> peerList;
        try {
            peerList = getPeerList(torrent);
            int numPeers = peerList.size();
            ExecutorService executorService = Executors.newFixedThreadPool(numPeers);
            for (String peer : peerList) {
                executorService.submit(() -> worker(torrent, peer));
            }
            executorService.shutdown();
            try {
                executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                System.out.println("Error waiting for executor service to terminate: " + e.getMessage());
            }
            // write the pieces to the file
            for (int i = 0; i < numPieces; i++) {
                bufferLock.lock();
                try {
                    Utils.writePieceToFile(storageFilePath, bufferMap.get(i));
                } finally {
                    bufferLock.unlock();
                }
            }
        } catch (Exception e) {
            System.out.println("Error getting peer list: " + e.getMessage());
        }
    }
    private static void worker(Torrent torrent, String peer) {
        while (true) {
            Integer pieceIndex = pieceQueue.poll();
            if (pieceIndex == null) {
                break;
            }
            // calculate the piece length based on the piece index
            try {
                byte[] piece = downloadPieceFromPeer(torrent, peer, pieceIndex);
                bufferLock.lock();
                try {
                    bufferMap.put(pieceIndex, piece);
                    System.out.println("Downloaded piece: " + pieceIndex);
                } finally {
                    bufferLock.unlock();
                }
            } catch (Exception e) {
                System.out.println("Error downloading piece: " + e.getMessage());
                pieceQueue.add(pieceIndex);
            }
        }
    }

    public static List<String> getPeerListFromMagnetInfo(Map<String, String> magnetInfoMap) {
        // parse the magnet URL to extract the xt, dn, and tr parameters
        // perform a GET request to the tracker URL
        String infoHash = new String(Utils.hexStringToByteArray(magnetInfoMap.get("xt").split(":")[2]),
                StandardCharsets.ISO_8859_1);
        Random random = new Random();
        byte[] peerIdBytes = new byte[10];
        random.nextBytes(peerIdBytes);
        String peerId = Utils.byteToHexString(peerIdBytes);
        String trackerURL = String.format("%s?info_hash=%s&dn=%s&port=%d&downloaded=%d&uploaded=%d&left=%d&compact=%d&peer_id=%s",
                magnetInfoMap.get("tr"),
                URLEncoder.encode(infoHash, StandardCharsets.ISO_8859_1),
                magnetInfoMap.get("dn"),
                PORT,
                0,
                0,
                1,
                1,
                peerId);
        System.out.println("Tracker URL: " + trackerURL);
        if (trackerURL == null) {
            throw new RuntimeException("No tracker URL found in magnet URL: " + trackerURL);
        }
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(new URI(trackerURL))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return getPeerListFromHTTPResponse(response);
        } catch (Exception e) {
            throw new RuntimeException("Error getting peer list from tracker: " + e.getMessage());
        }




    }

    public static Map<String, String> getParamsFromMagnetURL(String magnetURL) {
        Map<String, String> map = new HashMap<>();
        String[] parts = magnetURL.split("\\?");
        if (parts.length != 2) {
            throw new RuntimeException("Invalid magnet URL: " + magnetURL);
        }
        String[] params = parts[1].split("&");
        for (String param : params) {
            String[] keyValue = param.split("=");
            if (keyValue.length != 2) {
                throw new RuntimeException("Invalid parameter: " + param);
            }
            if (keyValue[0].equals("tr")) {
                map.put("tr", URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8));
            } else {
                map.put(keyValue[0], keyValue[1]);
            }
        }
        return map;
    }

    public static byte[] createExtensionHandshakeMessage(List<String> extensionList) {
        Map<String, Map<String, Integer>> extensionDict = new HashMap<>();
        Map<String, Integer> m = new HashMap<>();
        for (String extension : extensionList) {
            m.put(extension, new Random().nextInt(255) + 1);
        }
        extensionDict.put("m", m);
        byte[] extensionDictBytes = new Bencode(true).encode(extensionDict);
        // create byte array for the extension handshake message with a 4 byte length prefix, 1 byte message ID, 1 byte extension messageid, and the extension dictionary
        ByteBuffer buffer = ByteBuffer.allocate(4 + 1 + 1 + extensionDictBytes.length);
        buffer.putInt(1 + 1 + extensionDictBytes.length);
        buffer.put((byte) 20);
        buffer.put((byte) 0);
        buffer.put(extensionDictBytes);
        System.out.println("Extension handshake message created");
        return buffer.array();
    }

    public static Map<String, Object> parseExtensionHandshakeResponse(byte[] extensionHandshakeResponse) {
        byte[] extensionDictBytes = Arrays.copyOfRange(extensionHandshakeResponse, 2, extensionHandshakeResponse.length);
        Map<String, Object> extensionDict = new Bencode(false).decode(extensionDictBytes, Type.DICTIONARY);
        Map<String, Object> metaDataIDMap = new HashMap<>();
        Map<String, Object> m = (Map<String, Object>) extensionDict.get("m");
        for (Map.Entry<String, Object> entry : m.entrySet()) {
            metaDataIDMap.put(entry.getKey(), entry.getValue());
        }
        return metaDataIDMap;
    }

    public static byte[] createMetadataRequestMessage(int messageType, int pieceIndex, long extensionId) {
Map<String, Integer> metadataRequestDict = new HashMap<>();
        metadataRequestDict.put("msg_type", messageType);
        metadataRequestDict.put("piece", pieceIndex);
        byte[] metadataRequestDictBytes = new Bencode(true).encode(metadataRequestDict);
        // create byte array for the metadata request message with a 4 byte length prefix, 1 byte message ID, and the metadata request dictionary
        ByteBuffer buffer = ByteBuffer.allocate(4 + 1 + 1 + metadataRequestDictBytes.length);
        buffer.putInt(2 + metadataRequestDictBytes.length);
        buffer.put((byte) 20);
        buffer.put((byte) extensionId);
        buffer.put(metadataRequestDictBytes);
        System.out.println("Metadata request message created");
        return buffer.array();
    }

    public static Pair<TCPService, Long> performMagnetHandshake(String magnetURL) {
        Map<String, String> magnetInfo = TorrentDownloader.getParamsFromMagnetURL(magnetURL);
        System.out.println("Magnet Info: " + magnetInfo);
        List<String> peerList = TorrentDownloader.getPeerListFromMagnetInfo(magnetInfo);
        TCPService tcpService = null;
        for (String peer : peerList) {
            String peerIP = peer.split(":")[0];
            Integer peerPort = Integer.parseInt(peer.split(":")[1]);
            try {
                Socket socket = new Socket(peerIP, peerPort);
                tcpService = new TCPService(socket);
                TorrentDownloader.performHandshake(magnetInfo.get("xt").split(":")[2], tcpService, true);
                // wait for bitfield message
                byte[] bitfieldMessage = tcpService.waitForMessage();
                if (bitfieldMessage[0] != 5) {
                    System.out.println("Expected bitfield message, received different message type: " + bitfieldMessage[4]);
                }
                System.out.println("Received bitfield message");
                // send extension handshake
                List<String> extensionList = new ArrayList<>();
                extensionList.add("ut_metadata");
                byte[] extensionHandshakeMessage = TorrentDownloader.createExtensionHandshakeMessage(extensionList);
                tcpService.sendMessage(extensionHandshakeMessage);
                byte[] extensionHandshakeResponse = tcpService.waitForMessage();
                Map<String, Object> metaDataIDMap = TorrentDownloader.parseExtensionHandshakeResponse(extensionHandshakeResponse);
                System.out.println("Peer Metadata Extension ID: " + metaDataIDMap.get("ut_metadata"));
                return Pair.of(tcpService, (long) metaDataIDMap.get("ut_metadata"));
            } catch (Exception e) {
                System.out.println("Failed to connect to peer: " + peer + " - " + e.getMessage());
            }
        }
        return null;
    }
}
