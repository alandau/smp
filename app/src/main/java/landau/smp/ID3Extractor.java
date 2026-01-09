package landau.smp;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;

public class ID3Extractor {
    private final String filename;
    private String artist, album, title;

    private static final CharsetDecoder userDecoder = Charset.forName("windows-1251").newDecoder();
    private static final CharsetDecoder utf8Decoder = StandardCharsets.UTF_8.newDecoder();

    public ID3Extractor(String filename) {
        this.filename = filename;
    }

    public String getAlbum() {
        return album;
    }

    public String getTitle() {
        return title;
    }

    public String getArtist() {
        return artist;
    }

    public void extractMetadata() {
        try (RandomAccessFile raf = new RandomAccessFile(filename, "r")) {
            try {
                extractV2(raf);
            } catch (IOException e) {
                // continue
            }

            if (artist == null || album == null || title == null) {
                try {
                    extractV1(raf);
                } catch (IOException e) {
                    // continue
                }
            }
        } catch (FileNotFoundException e) {
            // continue
        } catch (IOException e) {
            // continue
        }
    }

    private void extractV1(RandomAccessFile raf) throws IOException {
        long fileSize = raf.length();
        if (fileSize < 128) {
            return;
        }
        // ID3v1 is at 128 bytes before EOF
        raf.seek(fileSize - 128);
        byte[] tagBytes = new byte[128];
        raf.readFully(tagBytes);

        if (tagBytes[0] != 'T' || tagBytes[1] != 'A' || tagBytes[2] != 'G') {
            // Not a ID3v1
            return;
        }

        if (title == null) {
            title = readId3V1String(tagBytes, 3, 30);
        }
        if (artist == null) {
            artist = readId3V1String(tagBytes, 33, 30);
        }
        if (album == null) {
            album = readId3V1String(tagBytes, 63, 30);
        }
    }

    @SuppressWarnings("SameParameterValue")
    private String readId3V1String(byte[] buffer, int offset, int len) {
        CharBuffer result;
        try {
            result = utf8Decoder.decode(ByteBuffer.wrap(buffer, offset, len));
        } catch (CharacterCodingException e) {
            try {
                result = userDecoder.decode(ByteBuffer.wrap(buffer, offset, len));
            } catch (CharacterCodingException ex) {
                return null;
            }
        }
        // trim() removes \0 padding at the end
        String trimmed = result.toString().trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void extractV2(RandomAccessFile raf) throws IOException {

    }
}
