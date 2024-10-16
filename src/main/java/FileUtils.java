import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class FileUtils {
    public static byte[] readTorrentFile(String torrentFilePath) {
        try {
        Path path = Paths.get(torrentFilePath);
        return Files.readAllBytes(path);
        } catch (IOException e) {
        throw new RuntimeException("Error reading torrent file: " + e.getMessage());
        }
    }
}
