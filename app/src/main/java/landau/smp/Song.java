package landau.smp;

import android.media.MediaMetadataRetriever;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;

@SuppressWarnings("WeakerAccess")
public class Song {
    private static final String TAG = Song.class.getSimpleName();

    private boolean extracted = false;
    private final String filename;
    private String artist, album, title;
    private int durationMs;

    private static final CharsetEncoder[] encoders = {
            Charset.forName("windows-1252").newEncoder(),
            Charset.forName("windows-1253").newEncoder(),
            Charset.forName("windows-1256").newEncoder(),
            Charset.forName("EUC-JP").newEncoder(),
            Charset.forName("GBK").newEncoder(),
            Charset.forName("Shift_JIS").newEncoder(),
    };
    private static final CharsetDecoder decoder = Charset.forName("windows-1251").newDecoder();
    private static final CharsetDecoder utf8decoder = StandardCharsets.UTF_8.newDecoder();
    private static final CharsetEncoder win1251encoder = Charset.forName("windows-1251").newEncoder();

    public Song(String filename) {
        this.filename = filename;
    }

    public void extractMetadata() {
        if (extracted) {
            return;
        }

        manuallyExtractId3V1();
        // Still need Android's extractor to get duration

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(filename);
        } catch (RuntimeException e) {
            // IllegalArgumentException (child of RuntimeException) is thrown if file is not found
            // or can't be opened. RuntimeException itself is thrown if metadata can't be extracted,
            // e.g. if it's a non-audio file. In this case, do nothing.
            try {
                retriever.release();
            } catch (IOException ignored) {}
            return;
        }
        if (artist == null)
            artist = getTagWithFallback(retriever, MediaMetadataRetriever.METADATA_KEY_ARTIST, 26);
        if (album == null)
            album = getTagWithFallback(retriever, MediaMetadataRetriever.METADATA_KEY_ALBUM, 25);
        if (title == null)
            title = getTagWithFallback(retriever, MediaMetadataRetriever.METADATA_KEY_TITLE, 31);
        try {
            //noinspection DataFlowIssue (extractMetadata may return null)
            durationMs = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        } catch (NumberFormatException e) {
            durationMs = 0;
        }
        try {
            retriever.release();
        } catch (IOException ignored) {}
        extracted = true;
    }

    public String getFilename() { return filename; }
    public String getArtist() { return artist; }
    public String getAlbum() { return album; }
    public String getTitle() { return title; }
    public int getDuration() { return durationMs; }

    private void manuallyExtractId3V1() {
        byte[] tagBytes = new byte[128];
        try (RandomAccessFile raf = new RandomAccessFile(filename, "r")) {
            long fileSize = raf.length();
            if (fileSize < 128) {
                return;
            }
            // ID3v1 is at 128 bytes before EOF
            raf.seek(fileSize - 128);
            raf.readFully(tagBytes);
        } catch (FileNotFoundException e) {
            return;
        } catch (IOException e) {
            return;
        }

        if (tagBytes[0] != 'T' || tagBytes[1] != 'A' || tagBytes[2] != 'G') {
            // Not a ID3v1
            return;
        }

        title = readId3V1String(tagBytes, 3, 30);
        artist = readId3V1String(tagBytes, 33, 30);
        album = readId3V1String(tagBytes, 63, 30);
    }

    @SuppressWarnings("SameParameterValue")
    private String readId3V1String(byte[] buffer, int offset, int len) {
        CharBuffer result;
        try {
            result = utf8decoder.decode(ByteBuffer.wrap(buffer, offset, len));
        } catch (CharacterCodingException e) {
            try {
                result = decoder.decode(ByteBuffer.wrap(buffer, offset, len));
            } catch (CharacterCodingException ex) {
                return null;
            }
        }
        // trim() removes \0 padding at the end
        String trimmed = result.toString().trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean isMaybeKoi8(String s) {
        boolean seenNonRuLetter = false;
        int state = 0; // 0 - non-letter, 1 - capital, 2 - small
        for (char c : s.toCharArray()) {
            switch (state) {
                case 0:
                    if (c >= 'А' && c <= 'Я') state = 1; // capital at word start -> maybe koi (e.g. second word)
                    else if (c >= 'а' && c <= 'я') state = 2;
                    // else, non-letter -> same state
                    break;
                case 1:
                    if (c >= 'а' && c <= 'я') return false; // small after capital -> not koi
                    //noinspection StatementWithEmptyBody
                    if (c >= 'А' && c <= 'Я') { /* do nothing */} // capital after capital -> maybe koi
                    else state = 0; // non-letter after capital
                    break;
                case 2:
                    if (c >= 'а' && c <= 'я') return false; // small after small -> not koi
                    if (c >= 'А' && c <= 'Я') state = 1; // capital after small -> maybe koi
                    else state = 0; // non-letter after small -> maybe koi
                    break;
            }
            if (state != 0) {
                seenNonRuLetter = true;
            }
        }
        return seenNonRuLetter;
    }
    private boolean isNameGood(String s) {
        int badChars = 0;
        for (int i = 0; i < s.length(); i = s.offsetByCodePoints(i, 1)) {
            int c = s.codePointAt(i);
            if (c >= 0x0600 && Character.getType(c) != Character.OTHER_SYMBOL) {
                badChars++;
            }
        }
        return badChars <= 1;
    }
    private String getTagWithFallback(MediaMetadataRetriever retriever, int key1, int key2) {
        String s = retriever.extractMetadata(key1);
        if (s == null) {
            s = retriever.extractMetadata(key2);
        }
        if (s == null) {
            return null;
        }
        s = s.trim();
        if (s.isEmpty()) {
            return null;
        }

        /*
        for (java.util.Map.Entry<String,Charset> d: Charset.availableCharsets().entrySet()) {
            for (java.util.Map.Entry<String,Charset> e: Charset.availableCharsets().entrySet()) {
//                CharBuffer res = Charset.forName("windows-1251").decode(e.getValue().encode(CharBuffer.wrap(s)));
                CharBuffer res = d.getValue().decode(e.getValue().encode(CharBuffer.wrap(s)));
                if (res.length() != 0 && res.get(0) >= 'А' && res.get(0) <= 'Я')
                    android.util.Log.i(TAG, d.getKey() + ","+e.getKey() + ": " + res);
            }
        }
        */

        try {
            String fixed = utf8decoder.decode(win1251encoder.encode(CharBuffer.wrap(s))).toString();
            if (isNameGood(fixed)) {
                return fixed;
            }
        } catch (CharacterCodingException e) {
            // continue
        }

        if (isNameGood(s) && isMaybeKoi8(s)) {
            try {
                String fixed = decoder.decode(Charset.forName("KOI8-R").encode(CharBuffer.wrap(s))).toString();
                if (isNameGood(fixed)) {
                    return fixed;
                }
            } catch (CharacterCodingException e) {
                // continue
            }
        }
        if (isNameGood(s)) {
            return s;
        }
        for (CharsetEncoder e : encoders) {
            try {
                String fixedName = decoder.decode(e.encode(CharBuffer.wrap(s))).toString();
                if (isNameGood(fixedName)) {
                    return fixedName;
                }
            } catch (CharacterCodingException ex) {
                // continue loop
            }
        }
        return null;
    }
}
