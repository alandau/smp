package landau.smp;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

@SuppressWarnings({"WeakerAccess", "unused"})
public class MetadataUtils {
    public static String getTitle(SharedPreferences prefs, Song song) {
        String s = song.getTitle();
        if (s == null || showFilename(prefs)) {
            s = new File(song.getFilename()).getName();
        }
        if (prefs.getBoolean("pref_underscore", true)) {
            s = s.replace('_', ' ');
        }
        return s;
    }

    public static String getTitleRCC(SharedPreferences prefs, Song song) {
        String s = getTitle(prefs, song);
        if (prefs.getBoolean("pref_transliterate", false)) {
            s = transliterate(s);
        }
        return s;
    }

    public static String getArtist(SharedPreferences prefs, Song song) {
        String s = song.getArtist();
        if (s == null || showFilename(prefs)) {
            s = new File(song.getFilename()).getParentFile().getParentFile().getName();
        }
        if (prefs.getBoolean("pref_underscore", true)) {
            s = s.replace('_', ' ');
        }
        return s;
    }

    public static String getArtistRCC(SharedPreferences prefs, Song song) {
        return maybeTransliterate(prefs, getArtist(prefs, song));
    }

    public static String getAlbum(SharedPreferences prefs, Song song) {
        String s = song.getAlbum();
        if (s == null || showFilename(prefs)) {
            s = new File(song.getFilename()).getParentFile().getName();
        }
        if (prefs.getBoolean("pref_underscore", true)) {
            s = s.replace('_', ' ');
        }
        return s;
    }

    public static String getAlbumRCC(SharedPreferences prefs, Song song) {
        return maybeTransliterate(prefs, getAlbum(prefs, song));
    }

    public static String getArtistAndAlbum(SharedPreferences prefs, Song song) {
        return getArtist(prefs, song) + " - " + getAlbum(prefs, song);
    }

    @SuppressLint("DefaultLocale")
    public static String formatTime(int timeMs) {
        int time = (timeMs + 999) / 1000;   // sec, round up
        String sec = String.format("%02d", time % 60);
        time /= 60;  // min
        String min = String.format("%02d", time % 60);
        time /= 60;  // hour
        String hour = "";
        if (time != 0) {
            hour = String.valueOf(time) + ":";
        }
        return hour + min + ":" + sec;
    }

    private static final Map<Character, String> transliterationMap = new HashMap<>();

    static {
        char[] abcRus = {'а','б','в','г','д','е','ё','ж','з','и','й','к','л','м','н','о','п','р','с','т','у','ф','х','ц','ч','ш','щ','ъ','ы','ь','э','ю','я',
                'ґ','є','і','ї'};
        String[] abcEng = {"a","b","v","g","d","e","e","zh","z","i","y","k","l","m","n","o","p","r","s","t","u","f","h","ts","ch","sh","sh'","","y","","e","yu","ya",
                "g","ye","i","yi"};
        for (int i = 0; i < abcRus.length; i++) {
            transliterationMap.put(abcRus[i], abcEng[i]);
            String upcase = "";
            if (!abcEng[i].isEmpty()) {
                upcase = String.valueOf(abcEng[i].charAt(0)).toUpperCase() + abcEng[i].substring(1);
            }
            transliterationMap.put(String.valueOf(abcRus[i]).toUpperCase().charAt(0), upcase);
        }
    }

    private static String transliterate(String s) {
        StringBuilder builder = new StringBuilder(s.length() + 10);
        for (int i = 0; i < s.length(); i++) {
            String replacement = transliterationMap.get(s.charAt(i));
            if (replacement != null) {
                builder.append(replacement);
            } else {
                builder.append(s.charAt(i));
            }
        }
        return builder.toString();
    }

    private static String maybeTransliterate(SharedPreferences prefs, String s) {
        return prefs.getBoolean("pref_transliterate", false) ? transliterate(s) : s;
    }

    private static boolean showFilename(SharedPreferences prefs) {
        return !prefs.getBoolean("pref_showMetadata", true);
    }
}
