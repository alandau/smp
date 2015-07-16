package landau.FMP;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class FMPActivity extends Activity {
    static final String TAG = FMPActivity.class.getSimpleName();

    private FMPService service;
    private SharedPreferences prefs;

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
        setContentView(R.layout.main);
        startService(new Intent(this, FMPService.class));
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getBoolean("pref_keepScreenOn", false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, FMPService.class), serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        if (service != null) {
            service.removeSongChangeNotification();
        }
        unbindService(serviceConnection);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            FMPService.SongChangeNotification onchange = new FMPService.SongChangeNotification() {
                @Override
                public void onNextSong(Song s) {
                    FMPActivity.this.onNextSong(s);
                }
            };
            service = ((FMPService.FMPServiceBinder)binder).getService();
            if (service.getState() == FMPService.State.INVALID) {
                service.init(Environment.getExternalStorageDirectory().getAbsolutePath() + "/Music", getSongList(), onchange);
            } else {
                service.connect(onchange);
            }
            updatePlayButtonText();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    private void updatePlayButtonText() {
        Button b = (Button)findViewById(R.id.btnPlayPause);
        if (service.getState() == FMPService.State.PLAYING) {
            b.setText("Pause");
        } else {
            b.setText("Play");
        }
    }

    private void onNextSong(Song song) {
        String s = MetadataUtils.getTitle(prefs, song);
        ((TextView)findViewById(R.id.lblTitle)).setText(s);

        s = song.getArtist();
        if (s == null) {
            s = new File(song.getFilename()).getParentFile().getParentFile().getName();
        }
        ((TextView)findViewById(R.id.lblArtist)).setText(s);

        s = song.getAlbum();
        if (s == null) {
            s = new File(song.getFilename()).getParentFile().getName();
        }
        ((TextView)findViewById(R.id.lblAlbum)).setText(s);
    }


    private void getSongListImpl(List<Song> result, File root) {
        FileFilter filter = new FileFilter() {
            @Override
            public boolean accept(File pathname) {
                return pathname.isDirectory() || pathname.getName().toLowerCase().endsWith(".mp3");
            }
        };
        for (File f : root.listFiles(filter)) {
            if (f.isDirectory()) {
                getSongListImpl(result, f);
            } else {
                result.add(new Song(f.getAbsolutePath()));
            }
        }
    }
    private List<Song> getSongList() {
        List<Song> songs = new ArrayList<Song>();
        getSongListImpl(songs, new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/Music"));
        Collections.shuffle(songs);
        return songs;
    }

    public void onBtnPlayPause(View view) {
        FMPService.State state = FMPService.State.INVALID;
        if (service != null) {
            state = service.getState();
        }
        switch (state) {
            case INVALID:
                break;
            case PLAYING:
            case PAUSED:
            case STOPPED:
                service.playpause();
                updatePlayButtonText();
                break;
        }
    }

    public void onPrevCick(View view) {
        if (service != null) {
            service.prev();
        }
    }

    public void onNextClick(View view) {
        if (service != null) {
            service.next();
        }
    }

    public void onExitClick(View view) {
        if (service != null) {
            service.removeSongChangeNotification();
            service.deinit();
            finish();
        }
    }

    public void onSettingsClick(View view) {
        startActivity(new Intent(this, SettingsActivity.class));
    }
}
