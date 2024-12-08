import com.dampcake.bencode.Bencode;
import org.apache.commons.lang3.tuple.Pair;

import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Main {

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
            Codec.decodeAndPrintBencodedString(bencodedValue);
        }
        case "info" -> {
            torrentFilePath = args[1];
            torrent = TorrentUtils.getTorrentFromPath(torrentFilePath);
            torrent.printInfo();
        }
        case "peers" -> {
            torrentFilePath = args[1];
            torrent = TorrentUtils.getTorrentFromPath(torrentFilePath);
            try {
                peerList = TorrentDownloader.getPeerList(torrent);
                for (String peer : peerList) {
                    System.out.println(peer);
                }
            } catch (Exception e) {
                System.out.println("Failed to get peer list: " + e.getMessage());
            }
        }
        case "handshake" -> {
            torrentFilePath = args[1];
            torrent = TorrentUtils.getTorrentFromPath(torrentFilePath);
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
            Map<String,String> magnetInfo = TorrentUtils.getParamsFromMagnetURL(magnetURI);
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
            torrent = TorrentUtils.getTorrentFromPath(torrentFilePath);
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
            torrent = TorrentUtils.getTorrentFromPath(torrentFilePath);
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
        Map<String, String> params = TorrentUtils.getParamsFromMagnetURL(magnetURL);
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
        List<String> pieceHashes = TorrentUtils.splitPieceHashes(pieceHashBytes, 20, new ArrayList<>());
        return Pair.of(new Torrent.Builder()
                .setTrackerURL(trackerURL)
                .setLength(((Number) metadataPieceDict.get("length")).longValue())
                .setInfoHash(infoHash)
                .setPieceLength(((Number) metadataPieceDict.get("piece length")).longValue())
                .setPieces(pieceHashes)
                .build(), tcpService);
    }
    }
