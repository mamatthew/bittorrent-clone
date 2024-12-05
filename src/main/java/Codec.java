import com.dampcake.bencode.Bencode;
import com.dampcake.bencode.Type;
import com.google.gson.Gson;

import java.util.List;
import java.util.Map;

public class Codec {

    private static final Gson gson = new Gson();

    public static void decodeAndPrintBencodedString(String bencodedString) {
        Object decoded;
        try {
            decoded = Codec.decodeBencodedBytes(bencodedString.getBytes());
        } catch (Exception e) {
            System.out.println("Problem encountered during decoding: " + e.getMessage());
            return;
        }
        System.out.println(gson.toJson(decoded));
    }
    public static Object decodeBencodedBytes(byte[] bencodedBytes) throws Exception {
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
            throw new Exception("Unsupported bencode type: " + bencodedBytes[0]);
        }
    }
}
