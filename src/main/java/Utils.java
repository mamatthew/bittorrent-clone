import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
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

    public static void writePieceToFile(String dest, byte[] piece) {
        Path path = Paths.get(dest);
        try {
            if (Files.exists(path)) {
                Files.write(path, piece, StandardOpenOption.APPEND);
            } else {
                Files.write(path, piece);
            }
        } catch (IOException e) {
            throw new RuntimeException("Error writing piece to file: " + e.getMessage());
        }

    }

    public static String byteToHexString(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            hexString.append(String.format("%02x", b));
        }
        return hexString.toString();
    }

    public static byte[] hexStringToByteArray(String hexString) {
        byte[] byteArray = new byte[hexString.length() / 2];
        for (int i = 0; i < byteArray.length; i++) {
            int index = i * 2;
            int j = Integer.parseInt(hexString.substring(index, index + 2), 16);
            byteArray[i] = (byte) j;
        }
        return byteArray;
    }

    public static String calculateSHA1(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(bytes);
            return byteToHexString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Error calculating SHA-1 hash: " + e.getMessage());
        }
    }

}