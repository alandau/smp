package landau.smp;

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
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

public class SMPActivity extends Activity {
    static final String TAG = SMPActivity.class.getSimpleName();

    private SMPService service;
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
        startService(new Intent(this, SMPService.class));
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getBoolean("pref_keepScreenOn", false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (prefs.getBoolean("pref_disableLockScreen", false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                                 WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        }
        gestureDetector = new GestureDetector(this, new SMPGestureDetector(this));
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
        bindService(new Intent(this, SMPService.class), serviceConnection, Context.BIND_AUTO_CREATE);
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

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater menuInflater = getMenuInflater();
        menuInflater.inflate(R.menu.actions, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_open:
                startActivityForResult(new Intent(this, SMPOpenActivity.class), 1);
                return true;
            case R.id.action_exit:
                if (service != null) {
                    service.removeSongChangeNotification();
                    service.deinit();
                }
                finish();
                return true;
            case R.id.action_settings:
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            SMPService.SongChangeNotification onchange = new SMPService.SongChangeNotification() {
                @Override
                public void onNextSong(Song s) {
                    SMPActivity.this.onNextSong(s);
                }

                @Override
                public void onStateChanged(SMPService.State state) {
                    updatePlayButtonText();
                    if (state == SMPService.State.PLAYING) {
                        startSeekbarTimer();
                    } else {
                        stopSeekbarTimer();
                    }
                }
            };
            service = ((SMPService.SMPServiceBinder)binder).getService();
            if (service.getState() == SMPService.State.INVALID) {
                String path = prefs.getString("state_lastPlayFolder", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).getAbsolutePath());
                service.init(getSongList(path), onchange);
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
        ImageButton b = (ImageButton)findViewById(R.id.btnPlayPause);
        if (service.getState() == SMPService.State.PLAYING) {
            b.setImageResource(android.R.drawable.ic_media_pause);
        } else {
            b.setImageResource(android.R.drawable.ic_media_play);
        }
    }

    private void onNextSong(Song song) {
        if (song == null) {
            ((TextView)findViewById(R.id.lblTitle)).setText("Select files");
            ((TextView)findViewById(R.id.lblArtist)).setText("");
            ((TextView)findViewById(R.id.lblAlbum)).setText("");
            seekBar.setMax(0);
            seekBar.setProgress(0);
            timeLabel.setText("");
            return;
        }
        ((TextView)findViewById(R.id.lblTitle)).setText(MetadataUtils.getTitle(prefs, song));
        ((TextView)findViewById(R.id.lblArtist)).setText(MetadataUtils.getArtist(prefs, song));
        ((TextView)findViewById(R.id.lblAlbum)).setText(MetadataUtils.getAlbum(prefs, song));

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
        File[] files = root.listFiles(filter);
        if (files == null) {
            if (!root.isDirectory()) {
                result.add(new Song(root.getAbsolutePath()));
            }
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                getSongListImpl(result, f);
            } else {
                result.add(new Song(f.getAbsolutePath()));
            }
        }
    }
    private List<Song> getSongList(String path) {
        List<Song> songs = new ArrayList<Song>();
        getSongListImpl(songs, new File(path));
        if (prefs.getBoolean("pref_shuffle", false)) {
            long seed;
            if (!prefs.contains("state_lastShuffleSeed")) {
                seed = new Random().nextLong();
                prefs.edit().putLong("state_lastShuffleSeed", seed).apply();
            } else {
                seed = prefs.getLong("state_lastShuffleSeed", 0);
            }
            Collections.shuffle(songs, new Random(seed));
        } else {
            Collections.sort(songs, new Comparator<Song>() {
                @Override
                public int compare(Song lhs, Song rhs) {
                    return lhs.getFilename().compareTo(rhs.getFilename());
                }
            });
        }
        return songs;
    }

    public void onBtnPlayPause(View view) {
        if (service != null && service.getState() != SMPService.State.INVALID) {
            service.playpause();
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

    private class SMPGestureDetector extends GestureDetector.SimpleOnGestureListener {
        private static final double FORBIDDEN_ZONE_MIN = Math.PI / 4 - Math.PI / 12;
        private static final double FORBIDDEN_ZONE_MAX = Math.PI / 4 + Math.PI / 12;
        private static final int MIN_VELOCITY_DP = 80;  // 0.5 inch/sec
        private static final int MIN_DISTANCE_DP = 80;  // 0.5 inch
        private final float MIN_VELOCITY_PX;
        private final float MIN_DISTANCE_PX;

        public SMPGestureDetector(Context context) {
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
                service.rewind();
                return true;
            }
            return false;
        }

        protected boolean onFlingDown() {
            if (service != null) {
                service.fastForward();
                return true;
            }
            return false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != 1 || resultCode != RESULT_OK) {
            return;
        }

        String path = data.getStringExtra("path");
        prefs.edit()
                .putString("state_lastPlayFolder", path)
                .remove("state_lastShuffleSeed")
                .remove("state_lastPlayedSong")
                .apply();
        if (service != null) {
            service.setSongList(getSongList(path));
            service.playpause();
        }
    }
}
