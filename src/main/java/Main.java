import com.dampcake.bencode.Bencode;
import com.dampcake.bencode.Type;
import com.google.gson.Gson;

import java.io.IOException;
import java.net.Socket;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;

public class Main {

    private static final Gson gson = new Gson();

    public static void main(String[] args) {
    String command = args[0];
    Torrent torrent;
    String torrentFilePath;
        List<String> peerList;
    String peerIPAndPort;
    Map<String, String> magnetInfo;
    switch(command) {
        case "decode":
            String bencodedValue = args[1];
            Object decoded;
            try {
              decoded = decodeBencode(bencodedValue.getBytes());
            } catch(RuntimeException e) {
              System.out.println(e.getMessage());
              return;
            }
            System.out.println(gson.toJson(decoded));
            break;
        case "info":
            torrentFilePath = args[1];
            torrent = TorrentDownloader.getTorrentFromPath(torrentFilePath);
            System.out.println("Tracker URL: " + torrent.getTrackerURL());
            System.out.println("Length: " + torrent.getLength());
            System.out.println("Info Hash: " + torrent.getInfoHash());
            System.out.println("Piece Length: " + torrent.getPieceLength());
            System.out.println("Piece Hashes:");
            List<String> pieces = torrent.getPieces();
            for (int i = 0; i < pieces.size(); i++) {
                System.out.println(pieces.get(i));
            }
            break;
        case "peers":
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

            break;
        case "handshake":
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
            break;
        case "magnet_parse":
            String magnetURI = args[1];
            magnetInfo = TorrentDownloader.getParamsFromMagnetURL(magnetURI);
            System.out.println("Tracker URL: " + magnetInfo.get("tr"));
            System.out.println("Info Hash: " + magnetInfo.get("xt").split(":")[2]);
            break;
        case "magnet_handshake":
            String magnetURL = args[1];
            magnetInfo = TorrentDownloader.getParamsFromMagnetURL(magnetURL);
            System.out.println("Magnet Info: " + magnetInfo);
            peerList = TorrentDownloader.getPeerListFromMagnetInfo(magnetInfo);
            for (String peer : peerList) {
                peerIP = peer.split(":")[0];
                peerPort = Integer.parseInt(peer.split(":")[1]);
                try (Socket socket = new Socket(peerIP, peerPort)){
                    TCPService tcpService = new TCPService(socket);
                    TorrentDownloader.performHandshake(magnetInfo.get("xt").split(":")[2], tcpService, true);
                    break;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

            }

            break;
        case "download_piece":
            String pieceStoragePath = args[2];
            torrentFilePath = args[3];
            torrent = TorrentDownloader.getTorrentFromPath(torrentFilePath);
            int pieceIndex = Integer.parseInt(args[4]);
            byte[] piece = TorrentDownloader.downloadPiece(torrent, pieceIndex);
            Utils.writePieceToFile(pieceStoragePath, piece);
            break;
        case "download":
            String storageFilePath = args[2];
            torrentFilePath = args[3];
            torrent = TorrentDownloader.getTorrentFromPath(torrentFilePath);
            // sout number of pieces
            System.out.println("Number of pieces: " + torrent.getPieces().size());
            TorrentDownloader.downloadTorrent(torrent, storageFilePath);
            break;
        default:
            System.out.println("Unknown command: " + command);
    }
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
