import com.dampcake.bencode.Bencode;
import com.dampcake.bencode.Type;
import com.google.gson.Gson;
import org.apache.commons.lang3.tuple.Pair;

import java.io.IOException;
import java.net.Socket;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Main {

    private static final Gson gson = new Gson();

    public static void main(String[] args) {
        String command = args[0];
        String torrentFilePath;
        Torrent torrent;
        List<String> peerList;
        String peerIPAndPort;
        String magnetURL;
        String pieceStoragePath;
    switch(command) {
        case "decode" -> {
            String bencodedValue = args[1];
            Object decoded;
            try {
                decoded = decodeBencode(bencodedValue.getBytes());
            } catch (RuntimeException e) {
                System.out.println(e.getMessage());
                return;
            }
            System.out.println(gson.toJson(decoded));
        }
        case "info" -> {
            torrentFilePath = args[1];
            torrent = TorrentDownloader.getTorrentFromPath(torrentFilePath);
            torrent.printInfo();
        }
        case "peers" -> {
            torrentFilePath = args[1];
            torrent = TorrentDownloader.getTorrentFromPath(torrentFilePath);
            try {
                peerList = TorrentDownloader.getPeerList(torrent);
            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            } catch (IOException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            for (String peer : peerList) {
                System.out.println(peer);
            }
        }
        case "handshake" -> {
            torrentFilePath = args[1];
            torrent = TorrentDownloader.getTorrentFromPath(torrentFilePath);
            peerIPAndPort = args[2];
            String peerIP = peerIPAndPort.split(":")[0];
            int peerPort = Integer.parseInt(peerIPAndPort.split(":")[1]);
            try (Socket socket = new Socket(peerIP, peerPort)){
                TCPService tcpService = new TCPService(socket);
                TorrentDownloader.performHandshake(torrent.getInfoHash(), tcpService, false);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        case "magnet_parse" -> {
            String magnetURI = args[1];
            Map<String,String> magnetInfo = TorrentDownloader.getParamsFromMagnetURL(magnetURI);
            System.out.println("Tracker URL: " + magnetInfo.get("tr"));
            System.out.println("Info Hash: " + magnetInfo.get("xt").split(":")[2]);
        }
        case "magnet_handshake" -> {
            magnetURL = args[1];
            TorrentDownloader.performMagnetHandshake(magnetURL);
        }
        case "magnet_info" -> {
            magnetURL = args[1];
            torrent = getTorrentFromMagnetURL(magnetURL).getLeft();
            torrent.printInfo();
        }
        case "download_piece" -> {
            pieceStoragePath = args[2];
            torrentFilePath = args[3];
            torrent = TorrentDownloader.getTorrentFromPath(torrentFilePath);
            int pieceIndex = Integer.parseInt(args[4]);
            byte[] piece = TorrentDownloader.downloadPiece(torrent, pieceIndex, false);
            Utils.writePieceToFile(pieceStoragePath, piece);
        }
        case "magnet_download_piece" -> {
            pieceStoragePath = args[2];
            magnetURL = args[3];
            int pieceIndex = Integer.parseInt(args[4]);
            Pair<Torrent, TCPService> pair = getTorrentFromMagnetURL(magnetURL);
            torrent = pair.getLeft();
            torrent.printInfo();
            TCPService tcpService = pair.getRight();
            try {
                byte[] piece = TorrentDownloader.downloadPieceHelper(tcpService, (int) torrent.getPieceLength(pieceIndex), pieceIndex);
                Utils.writePieceToFile(pieceStoragePath, piece);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        case "download" -> {
            String storageFilePath = args[2];
            torrentFilePath = args[3];
            torrent = TorrentDownloader.getTorrentFromPath(torrentFilePath);
            // sout number of pieces
            System.out.println("Number of pieces: " + torrent.getPieces().size());
            TorrentDownloader.downloadTorrent(torrent, storageFilePath, false);
        }
        case "magnet_download" -> {
            String storageFilePath = args[2];
            magnetURL = args[3];
            Pair<Torrent, TCPService> pair = getTorrentFromMagnetURL(magnetURL);
            torrent = pair.getLeft();
            TCPService tcpService = pair.getRight();
            try {
                tcpService.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            System.out.println("downloadTorrent");
            TorrentDownloader.downloadTorrent(torrent, storageFilePath, true);
        }
        default -> System.out.println("Unknown command: " + command);
    }
  }

    private static Pair<Torrent, TCPService> getTorrentFromMagnetURL(String magnetURL) {
        Map<String, String> params = TorrentDownloader.getParamsFromMagnetURL(magnetURL);
        String infoHash = params.get("xt").split(":")[2];
        String trackerURL = params.get("tr");
        Pair<TCPService, Long> handshakeResult = TorrentDownloader.performMagnetHandshake(magnetURL);
        TCPService tcpService = handshakeResult.getLeft();
        long extensionId = handshakeResult.getRight();
        if (tcpService == null) {
            throw new RuntimeException("Failed to connect to any peers");
        }
        System.out.println("Extension ID: " + extensionId);
        byte[] metadataRequestMessage = TorrentDownloader.createMetadataRequestMessage(0, 0, extensionId);
        tcpService.sendMessage(metadataRequestMessage);
        byte[] metadataResponse = tcpService.waitForMessage();
        Map<String, Object> metadataPieceDict = TorrentDownloader.getMetadataFromMessage(metadataResponse);
        String calculatedInfoHash = Utils.calculateSHA1(new Bencode(true).encode(metadataPieceDict));
        if (!calculatedInfoHash.equals(infoHash)) {
            throw new RuntimeException("Info hash mismatch, expected " + infoHash + " but got " + calculatedInfoHash);
        }
        byte[] pieceHashBytes = ((ByteBuffer) metadataPieceDict.get("pieces")).array();
        List<String> pieceHashes = Torrent.splitPieceHashes(pieceHashBytes, 20, new ArrayList<>());
        return Pair.of(new Torrent.Builder()
                .setTrackerURL(trackerURL)
                .setLength(((Number) metadataPieceDict.get("length")).longValue())
                .setInfoHash(infoHash)
                .setPieceLength(((Number) metadataPieceDict.get("piece length")).longValue())
                .setPieces(pieceHashes)
                .build(), tcpService);
    }

    public static Object decodeBencode(byte[] bencodedBytes) {
        Bencode bencode = new Bencode();
        if (Character.isDigit((char) bencodedBytes[0])) {
            String decodedString = bencode.decode(bencodedBytes, Type.STRING);
            return decodedString;
        } else if (bencodedBytes[0] == 'i') {
            Long decodedInt = bencode.decode(bencodedBytes, Type.NUMBER);
            return decodedInt;
        } else if (bencodedBytes[0] == 'l') {
            List<Object> decodedList = bencode.decode(bencodedBytes, Type.LIST);
            return decodedList;
        } else if (bencodedBytes[0] == 'd') {
            Map<String, Object> decodedDict = bencode.decode(bencodedBytes, Type.DICTIONARY);
            return decodedDict;
        } else {
            throw new RuntimeException("Unsupported bencode type");
        }
  }

}
