import com.dampcake.bencode.Bencode;
import com.dampcake.bencode.Type;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class Torrent {

    private final String trackerURL;

    private final long length;

    private final String infoHash;

    private final long pieceLength;

    private final List<String> pieces;

    public static Torrent fromBytes(byte[] fileBytes) {
        Bencode bencode = new Bencode(false);
        Map<String, Object> decodedDict = bencode.decode(fileBytes, Type.DICTIONARY);
        Map<String, Object> infoDict = (Map<String, Object>) decodedDict.get("info");
        String trackerURL = (String) decodedDict.get("announce");
        long length = (long) infoDict.get("length");
        long pieceLength = (long) infoDict.get("piece length");
        Bencode bencode2 = new Bencode(true);
        Map<String, Object> bencodedInfoDict = (Map<String, Object>) bencode2.decode(fileBytes, Type.DICTIONARY).get("info");
        byte[] pieceHashBytes = ((ByteBuffer) bencodedInfoDict.get("pieces")).array();
        List<String> pieces = splitPieceHashes(pieceHashBytes, 20, new ArrayList<>());
        String infoHash = Utils.calculateSHA1(bencode2.encode(bencodedInfoDict));

        return new Torrent.Builder()
                .setTrackerURL(trackerURL)
                .setLength(length)
                .setInfoHash(infoHash)
                .setPieceLength(pieceLength)
                .setPieces(pieces)
                .build();
    }

    private Torrent(Builder builder) {
        this.trackerURL = builder.trackerURL;
        this.length = builder.length;
        this.infoHash = builder.infoHash;
        this.pieceLength = builder.pieceLength;
        this.pieces = builder.pieces;
    }

    public void printInfo() {
        System.out.println("Tracker URL: " + trackerURL);
        System.out.println("Length: " + length);
        System.out.println("Info Hash: " + infoHash);
        System.out.println("Piece Length: " + pieceLength);
        System.out.println("Piece Hashes:");
        for (String piece : pieces) {
            System.out.println(piece);
        }
    }

    public static class Builder {
        private String trackerURL;
        private long length;
        private String infoHash;
        private long pieceLength;
        private List<String> pieces;

        public Builder setTrackerURL(String trackerURL) {
            this.trackerURL = trackerURL;
            return this;
        }

        public Builder setLength(long length) {
            this.length = length;
            return this;
        }

        public Builder setInfoHash(String infoHash) {
            this.infoHash = infoHash;
            return this;
        }

        public Builder setPieceLength(long pieceLength) {
            this.pieceLength = pieceLength;
            return this;
        }

        public Builder setPieces(List<String> pieces) {
            this.pieces = pieces;
            return this;
        }

        public Torrent build() {
            Torrent torrent = new Torrent(this);
            return torrent;
        }
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

    public List<String> getPieces() {
        return pieces;
    }
}
