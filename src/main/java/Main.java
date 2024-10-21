import com.dampcake.bencode.Bencode;
import com.dampcake.bencode.Type;
import com.google.gson.Gson;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class Main {
  private static final Gson gson = new Gson();
  private static final int PORT = 6881;

  public static void main(String[] args) throws Exception {
    String command = args[0];
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
            String torrentFilePath = args[1];
            byte[] torrentFileBytes = Utils.readTorrentFile(torrentFilePath);
            Torrent torrent = new Torrent(torrentFileBytes);
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
            String torrentFilePath2 = args[1];
            byte[] torrentFileBytes2 = Utils.readTorrentFile(torrentFilePath2);
            Torrent torrent2 = new Torrent(torrentFileBytes2);
            String url = torrent2.getTrackerURL();
            String infoHash = new String(Utils.hexStringToByteArray(torrent2.getInfoHash()),
                    StandardCharsets.ISO_8859_1);
            Random random = new Random();
            byte[] peerIdBytes = new byte[10];
            random.nextBytes(peerIdBytes);
            String peerId = Utils.byteToHexString(peerIdBytes);
            int uploaded = 0;
            int downloaded = 0;
            long left = torrent2.getLength();
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

            for (int i = 0; i < peersBytes.length; i += 6) {
                String ip = String.format("%d.%d.%d.%d", peersBytes[i] & 0xff, peersBytes[i + 1] & 0xff,
                        peersBytes[i + 2] & 0xff, peersBytes[i + 3] & 0xff);
                int port = ((peersBytes[i + 4] & 0xff) << 8) | (peersBytes[i + 5] & 0xff);
                System.out.println(ip + ":" + port);
            }

            break;
        case "handshake":
            String torrentFilePath3 = args[1];
            System.out.println("Torrent file path: " + torrentFilePath3);
            String peerIPAndPort = args[2];
            String peerIP = peerIPAndPort.split(":")[0];
            System.out.println("Peer IP: " + peerIP);
            int peerPort = Integer.parseInt(peerIPAndPort.split(":")[1]);
            System.out.println("Peer Port: " + peerPort);
            byte[] torrentFileBytes3 = Utils.readTorrentFile(torrentFilePath3);
            Torrent torrent3 = new Torrent(torrentFileBytes3);
            String infoHash2 = torrent3.getInfoHash();
            System.out.println("Info Hash: " + infoHash2);
            byte[] handshakeMessage = createHandshakeMessage(infoHash2);
            System.out.println("Handshake message: " + new String(handshakeMessage, StandardCharsets.ISO_8859_1));
            TCPClient tcpClient = new TCPClient();
            byte[] handshakeResponse = tcpClient.sendAndReceive(peerIP, peerPort, handshakeMessage);
            byte[] peerIdBytes3 = Arrays.copyOfRange(handshakeResponse, handshakeResponse.length - 20, handshakeResponse.length);
            String peerId2 = Utils.byteToHexString(peerIdBytes3);
            System.out.println("Peer ID: " + peerId2);
            break;
        default:
            System.out.println("Unknown command: " + command);
    }
  }

    private static byte[] createHandshakeMessage(String infoHash2) {
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
