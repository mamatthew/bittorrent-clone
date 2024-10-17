import com.dampcake.bencode.Bencode;
import com.dampcake.bencode.Type;

import java.util.Map;

public class Torrent {

    private String trackerURL;

    private long length;

    private String infoHash;

    public Torrent(byte[] fileBytes) {
        Bencode bencode = new Bencode(false);
        Map<String, Object> decodedDict = bencode.decode(fileBytes, Type.DICTIONARY);
        Map<String, Object> infoDict = (Map<String, Object>) decodedDict.get("info");
        this.trackerURL = (String) decodedDict.get("announce");
        this.length = (long) infoDict.get("length");
        Bencode bencode2 = new Bencode(true);
        this.infoHash = Utils.calculateSHA1(bencode2.encode((Map<String,Object>)bencode2.decode(fileBytes, Type.DICTIONARY).get("info")));
    }

    public String getTrackerURL() {
        return trackerURL;
    }

    public long getLength() {
        return length;
    }

    public String getInfoHash() {
        return infoHash;
    }
}
