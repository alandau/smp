package landau.smp;

import android.media.MediaMetadataRetriever;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;

@SuppressWarnings("WeakerAccess")
public class Song {
    private static final String TAG = Song.class.getSimpleName();

    private boolean extracted = false;
    private String filename;
    private String artist, album, title;
    private int durationMs;

    private static CharsetEncoder encoder = Charset.forName("windows-1252").newEncoder();
    private static CharsetDecoder decoder = Charset.forName("windows-1251").newDecoder();

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
            retriever.release();
            return;
        }
        artist = getTagWithFallback(retriever, MediaMetadataRetriever.METADATA_KEY_ARTIST, 26);
        album = getTagWithFallback(retriever, MediaMetadataRetriever.METADATA_KEY_ALBUM, 25);
        title = getTagWithFallback(retriever, MediaMetadataRetriever.METADATA_KEY_TITLE, 31);
        try {
            durationMs = Integer.valueOf(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        } catch (NumberFormatException e) {
            durationMs = 0;
        }
        retriever.release();
        extracted = true;
    }

    public String getFilename() { return filename; }
    public String getArtist() { return artist; }
    public String getAlbum() { return album; }
    public String getTitle() { return title; }
    public int getDuration() { return durationMs; }

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
        try {
            return decoder.decode(encoder.encode(CharBuffer.wrap(s))).toString();
        } catch (CharacterCodingException e) {
            return s;
        }
    }
}
