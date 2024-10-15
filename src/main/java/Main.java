import com.dampcake.bencode.Bencode;
import com.dampcake.bencode.Type;
import com.google.gson.Gson;

public class Main {
  private static final Gson gson = new Gson();

  public static void main(String[] args) throws Exception {
    // You can use print statements as follows for debugging, they'll be visible when running tests.
    String command = args[0];
    if("decode".equals(command)) {
        String bencodedValue = args[1];
        Object decoded;
        try {
          decoded = decodeBencode(bencodedValue);
        } catch(RuntimeException e) {
          System.out.println(e.getMessage());
          return;
        }
        System.out.println(gson.toJson(decoded));

    } else {
      System.out.println("Unknown command: " + command);
    }

  }

  static Object decodeBencode(String bencodedString) {
    Bencode bencode = new Bencode();
    byte[] bencodedBytes = bencodedString.getBytes();
    if (Character.isDigit(bencodedString.charAt(0))) {
      String decodedString = bencode.decode(bencodedBytes, Type.STRING);
        return decodedString;
    } else if (bencodedString.charAt(0) == 'i') {
        Long decodedInt = bencode.decode(bencodedBytes, Type.NUMBER);
        return decodedInt;
    } else {
      throw new RuntimeException("Unsupported bencode type");
    }
  }

}
