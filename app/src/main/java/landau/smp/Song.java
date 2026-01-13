package landau.smp;

import android.media.MediaMetadataRetriever;

import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;

@SuppressWarnings("WeakerAccess")
public class Song {
    private static final String TAG = Song.class.getSimpleName();

    private boolean extracted = false;
    private final String filename;
    private String artist, album, title;
    private int durationMs;

    private static final CharsetEncoder encoder = Charset.forName("windows-1252").newEncoder();
    private final CharsetDecoder decoder;

    public Song(String filename, CharsetDecoder assumedDecoder) {
        this.filename = filename;
        this.decoder = assumedDecoder;
    }

    public void extractMetadata() {
        if (extracted) {
            return;
        }

        ID3Extractor id3Extractor = new ID3Extractor(filename, decoder);
        id3Extractor.extractMetadata();
        artist = id3Extractor.getArtist();
        album = id3Extractor.getAlbum();
        title = id3Extractor.getTitle();

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
