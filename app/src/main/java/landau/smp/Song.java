package landau.smp;

import android.media.MediaMetadataRetriever;

import java.io.IOException;
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
        artist = getTagWithFallback(retriever, MediaMetadataRetriever.METADATA_KEY_ARTIST, 26);
        album = getTagWithFallback(retriever, MediaMetadataRetriever.METADATA_KEY_ALBUM, 25);
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
        for (Map.Entry<String,Charset> d: Charset.availableCharsets().entrySet()) {
            for (Map.Entry<String,Charset> e: Charset.availableCharsets().entrySet()) {
//                CharBuffer res = Charset.forName("windows-1251").decode(e.getValue().encode(CharBuffer.wrap(s)));
                CharBuffer res = d.getValue().decode(e.getValue().encode(CharBuffer.wrap(s)));
                if (res.length() != 0 && res.get(0) >= 'А' && res.get(0) <= 'Я')
                    Log.i(TAG, d.getKey() + ","+e.getKey() + ": " + res);
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
