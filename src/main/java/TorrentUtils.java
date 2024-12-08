import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TorrentUtils {
    public static List<String> splitPieceHashes(byte[] pieces, int pieceLength, List<String> pieceHashes) {
        for (int i = 0; i < pieces.length; i += pieceLength) {
            String pieceHashString = Utils.byteToHexString(Arrays.copyOfRange(pieces, i, i + pieceLength));
            pieceHashes.add(pieceHashString);
        }
        return pieceHashes;
    }

    static Torrent getTorrentFromPath(String torrentFilePath) {
        byte[] torrentFileBytes = Utils.readTorrentFile(torrentFilePath);
        Torrent torrent = Torrent.fromBytes(torrentFileBytes);
        return torrent;
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
}
