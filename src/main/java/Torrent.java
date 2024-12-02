import com.dampcake.bencode.Bencode;
import com.dampcake.bencode.Type;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class Torrent {

    private String trackerURL;

    private long length;

    private String infoHash;

    private long pieceLength;

    private List<String> pieces;

    public Torrent(byte[] fileBytes) {
        Bencode bencode = new Bencode(false);
        Map<String, Object> decodedDict = bencode.decode(fileBytes, Type.DICTIONARY);
        Map<String, Object> infoDict = (Map<String, Object>) decodedDict.get("info");
        this.trackerURL = (String) decodedDict.get("announce");
        this.length = (long) infoDict.get("length");
        this.pieceLength = (long) infoDict.get("piece length");
        Bencode bencode2 = new Bencode(true);
        Map<String, Object> bencodedInfoDict = (Map<String, Object>) bencode2.decode(fileBytes, Type.DICTIONARY).get("info");
        byte[] pieceHashBytes = ((ByteBuffer) bencodedInfoDict.get("pieces")).array();
        this.pieces = splitPieceHashes(pieceHashBytes, 20, new ArrayList<>());
        this.infoHash = Utils.calculateSHA1(bencode2.encode(bencodedInfoDict));
    }

    public static List<String> splitPieceHashes(byte[] pieces, int pieceLength, List<String> pieceHashes) {
        for (int i = 0; i < pieces.length; i += pieceLength) {
            String pieceHashString = Utils.byteToHexString(Arrays.copyOfRange(pieces, i, i + pieceLength));
            pieceHashes.add(pieceHashString);
        }
        return pieceHashes;
    }

    public String getTrackerURL() {
        return trackerURL;
    }

    public long getLength() {
        return length;
    }

    public long getPieceLength(int index) {
        if (index * pieceLength + pieceLength > length) {
            return length - index * pieceLength;
        }
        return pieceLength;
    }

    public String getInfoHash() {
        return infoHash;
    }

    public long getPieceLength() {
        return pieceLength;
    }

    public List<String> getPieces() {
        return pieces;
    }
}
