import com.dampcake.bencode.Bencode;
import com.dampcake.bencode.Type;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
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
            performHandshake(torrent, tcpService);
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
                                                  byte[] expectedInfoHash) {
        if (response[0] != 19) {
            throw new RuntimeException("Invalid protocol length: " + response[0]);
        }
        byte[] protocolBytes = Arrays.copyOfRange(response, 1, 20);
        String protocol = new String(protocolBytes, StandardCharsets.ISO_8859_1);
        if (!"BitTorrent protocol".equals(protocol)) {
            throw new RuntimeException("Invalid protocol: " + protocol);
        }
        byte[] receivedInfoHash = Arrays.copyOfRange(response, 28, 48);
        if (!Arrays.equals(expectedInfoHash, receivedInfoHash)) {
            throw new RuntimeException("Info hash mismatch");
        }
    }


    static void performHandshake(Torrent torrent, TCPService tcpService) {
        String infoHash = torrent.getInfoHash();
        byte[] handshakeMessage = createHandshakeMessage(infoHash);
        tcpService.sendMessage(handshakeMessage);
        byte[] handshakeResponse = tcpService.waitForHandshakeResponse();
        validateHandshakeResponse(handshakeResponse, Utils.hexStringToByteArray(infoHash));
        byte[] peerIdBytes = Arrays.copyOfRange(handshakeResponse, handshakeResponse.length - 20, handshakeResponse.length);
        String peerId = Utils.byteToHexString(peerIdBytes);
    }

    static byte[] createHandshakeMessage(String infoHash2) {
        // create a handshake message to send to the peer
        ByteArrayOutputStream handshakeMessage = new ByteArrayOutputStream();
        try {
            handshakeMessage.write(19);
            handshakeMessage.write("BitTorrent protocol".getBytes());
            handshakeMessage.write(new byte[] {0,0,0,0,0,0,0,0});
            handshakeMessage.write(Utils.hexStringToByteArray(infoHash2));
            handshakeMessage.write("ABCDEFGHIJKLMNOPQRST".getBytes());
            return handshakeMessage.toByteArray();
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
}
