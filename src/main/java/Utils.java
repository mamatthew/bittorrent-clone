import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class Utils {
    public static byte[] readTorrentFile(String torrentFilePath) {
        try {
            Path path = Paths.get(torrentFilePath);
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new RuntimeException("Error reading torrent file: " + e.getMessage());
        }
    }

    public static String calculateSHA1(byte[] encodedInfoDict) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(encodedInfoDict);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Error calculating SHA-1 hash: " + e.getMessage());
        }
    }
}