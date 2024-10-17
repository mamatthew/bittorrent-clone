import com.dampcake.bencode.Bencode;
import com.dampcake.bencode.Type;
import com.google.gson.Gson;

import java.util.List;
import java.util.Map;

public class Main {
  private static final Gson gson = new Gson();

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
            byte[] torrentFileBytes = FileUtils.readTorrentFile(torrentFilePath);
            Map<String, Object> decodedDict = (Map<String, Object>) decodeBencode(torrentFileBytes);
            Map<String, Object> infoDict = (Map<String, Object>) decodedDict.get("info");
            System.out.println("Tracker URL: " + decodedDict.get("announce"));
            System.out.println("Length: " + infoDict.get("length"));
            break;
        default:
            System.out.println("Unknown command: " + command);
    }
  }

  static Object decodeBencode(byte[] bencodedBytes) {
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
