package landau.FMP;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.SeekBar;
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
    private GestureDetector gestureDetector;
    private Handler handler = new Handler();
    private SeekBar seekBar;
    private TextView timeLabel;
    private Runnable seekbarUpdater = new Runnable() {
        @Override
        public void run() {
            if (service != null) {
                seekBar.setProgress(service.getCurrentTime() / 1000);
                timeLabel.setText(String.format("%s / %s", MetadataUtils.formatTime(service.getCurrentTime() / 1000 * 1000),
                        MetadataUtils.formatTime(service.getDuration())));
                handler.postDelayed(this, 250);
            }
        }
    };

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
        gestureDetector = new GestureDetector(this, new FMPGestureDetector(this));
        seekBar = (SeekBar)findViewById(R.id.barTime);
        timeLabel = (TextView)findViewById(R.id.lblTime);
        findViewById(R.id.lytMainSpace).setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return gestureDetector.onTouchEvent(event);
            }
        });
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                service.seek(progress * 1000);
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
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
        stopSeekbarTimer();
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

                @Override
                public void onStateChanged(FMPService.State state) {
                    updatePlayButtonText();
                    if (state == FMPService.State.PLAYING) {
                        startSeekbarTimer();
                    } else {
                        stopSeekbarTimer();
                    }
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

    private void startSeekbarTimer() {
        seekbarUpdater.run();
    }

    private void stopSeekbarTimer() {
        handler.removeCallbacks(seekbarUpdater);
    }

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

        seekbarUpdater.run();
        seekBar.setMax((song.getDuration() + 999) / 1000);
        seekBar.setProgress(0);
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

    private class FMPGestureDetector extends GestureDetector.SimpleOnGestureListener {
        private static final double FORBIDDEN_ZONE_MIN = Math.PI / 4 - Math.PI / 12;
        private static final double FORBIDDEN_ZONE_MAX = Math.PI / 4 + Math.PI / 12;
        private static final int MIN_VELOCITY_DP = 80;  // 0.5 inch/sec
        private static final int MIN_DISTANCE_DP = 80;  // 0.5 inch
        private final float MIN_VELOCITY_PX;
        private final float MIN_DISTANCE_PX;

        public FMPGestureDetector(Context context) {
            float density = context.getResources().getDisplayMetrics().density;
            MIN_VELOCITY_PX = MIN_VELOCITY_DP * density;
            MIN_DISTANCE_PX = MIN_DISTANCE_DP * density;
        }

        @Override
        public boolean onDown(MotionEvent e) {
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            onBtnPlayPause(null);
            return false;
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            float velocitySquared = velocityX*velocityX + velocityY*velocityY;
            if (velocitySquared < MIN_VELOCITY_PX * MIN_VELOCITY_PX) {
                // too slow
                return false;
            }

            float deltaX = e2.getX() - e1.getX();
            float deltaY = e2.getY() - e1.getY();

            if (Math.abs(deltaX) < MIN_DISTANCE_PX && Math.abs(deltaY) < MIN_DISTANCE_PX) {
                // small movement
                return false;
            }

            double angle = Math.atan2(Math.abs(deltaY), Math.abs(deltaX));
            if (angle > FORBIDDEN_ZONE_MIN && angle < FORBIDDEN_ZONE_MAX) {
                return false;
            }

            if (Math.abs(deltaX) > Math.abs(deltaY)) {
                if (deltaX > 0) {
                    return onFlingRight();
                } else {
                    return onFlingLeft();
                }
            } else {
                if (deltaY > 0) {
                    return onFlingDown();
                } else {
                    return onFlingUp();
                }
            }
        }

        @Override
        public void onLongPress(MotionEvent e) {
            Log.i(TAG, "long press");
        }

        protected boolean onFlingRight() {
            onNextClick(null);
            return true;
        }

        protected boolean onFlingLeft() {
            onPrevCick(null);
            return true;
        }

        protected boolean onFlingUp() {
            if (service != null) {
                int newtime = service.getCurrentTime() - 10000;
                if (newtime < 0) {
                    newtime = 0;
                }
                service.seek(newtime);
                return true;
            }
            return false;
        }

        protected boolean onFlingDown() {
            if (service != null) {
                int newtime = service.getCurrentTime() + 10000;
                if (newtime > service.getDuration()) {
                    newtime = service.getDuration();
                }
                service.seek(newtime);
                return true;
            }
            return false;
        }
    }
}
