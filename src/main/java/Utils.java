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
            byte[] fileContents = Files.readAllBytes(path);
            return fileContents;

        } catch (IOException e) {
            throw new RuntimeException("Error reading torrent file: " + e.getMessage());
        }
    }

    public static String byteToHexString(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            hexString.append(String.format("%02x", b));
        }
        return hexString.toString();
    }

    public static String calculateSHA1(byte[] encodedInfoDict) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(encodedInfoDict);
            return byteToHexString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Error calculating SHA-1 hash: " + e.getMessage());
        }
    }
}