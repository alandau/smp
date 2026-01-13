package landau.smp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
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

import androidx.annotation.NonNull;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class SMPActivity extends Activity {
    static final String TAG = SMPActivity.class.getSimpleName();

    private SMPService service;
    private SharedPreferences prefs;
    private GestureDetector gestureDetector;
    private final Handler handler = new Handler();
    private SeekBar seekBar;
    private TextView timeLabel;
    private final Runnable seekbarUpdater = new Runnable() {
        @Override
        public void run() {
            if (service != null) {
                seekBar.setProgress(service.getCurrentTime() / 1000);
                updateTimeLabel();
                handler.postDelayed(this, 250);
            }
        }
    };

    /**
     * Called when the activity is first created.
     */
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //noinspection deprecation
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
        setContentView(R.layout.main);
        startService(new Intent(this, SMPService.class));
        //noinspection deprecation
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getBoolean("pref_keepScreenOn", false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (prefs.getBoolean("pref_disableLockScreen", false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                                 WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        }
        gestureDetector = new GestureDetector(this, new SMPGestureDetector(this));
        seekBar = findViewById(R.id.barTime);
        timeLabel = findViewById(R.id.lblTime);
        findViewById(R.id.lytMainSpace).setOnTouchListener((v, event) -> gestureDetector.onTouchEvent(event));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && service != null) {
                    service.seek(progress * 1000);
                    updateTimeLabel();
                }
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
        if (hasOrRequestStoragePermission()) {
            bindService(new Intent(this, SMPService.class), serviceConnection, Context.BIND_AUTO_CREATE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasOrRequestPermission(Manifest.permission.POST_NOTIFICATIONS, 2);
        }
    }

    @Override
    protected void onStop() {
        if (service != null) {
            service.removeSongChangeNotification();
            unbindService(serviceConnection);
        }
        stopSeekbarTimer();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == 1 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            recreate();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater menuInflater = getMenuInflater();
        menuInflater.inflate(R.menu.actions, menu);
        if (!prefs.getString("pref_shutoffTimer", "0").equals("0")) {
            MenuItem item = menu.findItem(R.id.action_viewShutoff);
            item.setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        }
        if (!prefs.getString("pref_recentCount", "5").equals("0")) {
            MenuItem item = menu.findItem(R.id.action_recent);
            item.setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        }
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.action_open) {
            if (hasOrRequestStoragePermission()) {
                startActivityForResult(new Intent(this, SMPOpenActivity.class), 1);
            }
            return true;
        } else if (itemId == R.id.action_exit) {
            if (service != null) {
                service.removeSongChangeNotification();
                service.deinit();
            }
            finish();
            return true;
        } else if (itemId == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        } else if (itemId == R.id.action_viewShutoff) {
            showShutoffDialog();
            return true;
        } else if (itemId == R.id.action_recent) {
            showRecentsDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @SuppressWarnings("SameParameterValue")
    private boolean hasOrRequestPermission(String permission, int requestCode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            // Permission already granted
            return true;
        }
        requestPermissions(new String[] {permission}, requestCode);
        return false;
    }

    private boolean hasOrRequestStoragePermission() {
        String permission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_EXTERNAL_STORAGE
                : Manifest.permission.READ_MEDIA_AUDIO;
        return hasOrRequestPermission(permission, 1);
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
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
                String path = prefs.getString("state_lastPlayFolder", "");
                if (path.isEmpty()) {
                    path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).getAbsolutePath();
                    prefs.edit().putString("state_lastPlayFolder", path).apply();
                }
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

    private void updateTimeLabel() {
        if (service != null) {
            timeLabel.setText(String.format("%s / %s", MetadataUtils.formatTime(service.getCurrentTime() / 1000 * 1000),
                    MetadataUtils.formatTime(service.getDuration())));
        }
    }

    private void updatePlayButtonText() {
        ImageButton b = findViewById(R.id.btnPlayPause);
        if (service.getState() == SMPService.State.PLAYING) {
            b.setImageResource(android.R.drawable.ic_media_pause);
        } else {
            b.setImageResource(android.R.drawable.ic_media_play);
        }
    }

    @SuppressLint("SetTextI18n")
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

        seekBar.setMax((song.getDuration() + 999) / 1000);
        seekBar.setProgress(0);
        seekbarUpdater.run();
    }


    private void getSongListImpl(List<Song> result, File root) {
        String assumedEncoding = prefs.getString("pref_assumedEncoding", "windows-1251");
        CharsetDecoder decoder = Charset.forName(assumedEncoding).newDecoder();
        File[] files = root.listFiles();
        if (files == null) {
            if (!root.isDirectory()) {
                result.add(new Song(root.getAbsolutePath(), decoder));
            }
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                getSongListImpl(result, f);
            } else {
                result.add(new Song(f.getAbsolutePath(), decoder));
            }
        }
    }
    private List<Song> getSongList(String path) {
        List<Song> songs = new ArrayList<>();
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
            //noinspection ComparatorCombinators (not available in API 16)
            Collections.sort(songs, (lhs, rhs) -> lhs.getFilename().compareTo(rhs.getFilename()));
        }
        return songs;
    }

    public void onBtnPlayPause(View view) {
        if (service != null && service.getState() != SMPService.State.INVALID) {
            service.playpause();
        }
    }

    public void onPrevClick(View view) {
        if (service != null) {
            service.prev();
        }
    }

    public void onNextClick(View view) {
        if (service != null) {
            service.next();
        }
    }

    @SuppressWarnings("WeakerAccess")
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
        public boolean onDown(@NonNull MotionEvent e) {
            return true;
        }

        @Override
        public boolean onDoubleTap(@NonNull MotionEvent e) {
            onBtnPlayPause(null);
            return false;
        }

        @Override
        public boolean onFling(MotionEvent e1, @NonNull MotionEvent e2, float velocityX, float velocityY) {
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
        public void onLongPress(@NonNull MotionEvent e) {
            Log.i(TAG, "long press");
        }

        protected boolean onFlingRight() {
            onNextClick(null);
            return true;
        }

        protected boolean onFlingLeft() {
            onPrevClick(null);
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

        String newPath = data.getStringExtra("path");
        playNewPath(newPath);
    }

    private void playNewPath(String newPath) {
        String oldPath = prefs.getString("state_lastPlayFolder", "");
        String recentsString = prefs.getString("state_recentPaths", "");
        ArrayList<String> recents = recentsString.isEmpty()
                ? new ArrayList<>()
                : new ArrayList<>(Arrays.asList(recentsString.split(":")));
        //noinspection StatementWithEmptyBody
        while (recents.remove(newPath)) {}  // Remove all
        if (!oldPath.isEmpty() && !oldPath.equals(newPath)) {
            recents.add(oldPath);
        }

        int recentCount = 5;
        try {
            recentCount = Integer.parseInt(prefs.getString("pref_recentCount", "5"));
        } catch (NumberFormatException ignored) {}
        while (recents.size() > recentCount) {
            recents.remove(0);
        }

        prefs.edit()
                .putString("state_lastPlayFolder", newPath)
                .remove("state_lastShuffleSeed")
                .remove("state_lastPlayedSong")
                .putString("state_recentPaths", String.join(":", recents))
                .apply();
        if (service != null) {
            service.setSongList(getSongList(newPath));
            service.playpause();
        }
    }
    private void showShutoffDialog() {
        if (service == null) {
            return;
        }
        int delay = (int)service.getShutoffDelayMs();
        String timeoutStr = delay == -1 ? "Never" : MetadataUtils.formatTime(delay);
        new AlertDialog.Builder(this)
                .setTitle(String.format("Current timeout: %s\nChoose new timeout:", timeoutStr))
                .setItems(getResources().getStringArray(R.array.pref_shutoffTimer_entries), (dialog, which) -> {
                    if (service == null) {
                        return;
                    }
                    String newTimeoutStr = getResources().getStringArray(R.array.pref_shutoffTimer_values)[which];
                    service.setShutoffDelayResourceStr(newTimeoutStr);
                })
                .setNegativeButton("Cancel", (dialog, which) -> {})
                .show();
    }

    private void showRecentsDialog() {
        String currentPath = prefs.getString("state_lastPlayFolder", "");
        String recentsString = prefs.getString("state_recentPaths", "");
        ArrayList<String> recents = recentsString.isEmpty()
                ? new ArrayList<>()
                : new ArrayList<>(Arrays.asList(recentsString.split(":")));
        recents.add(currentPath);
        Collections.reverse(recents);

        CharSequence[] recentTitles = new CharSequence[recents.size()];
        for (int i = 0; i < recents.size(); i++) {
            String fullPath = recents.get(i);
            recentTitles[i] = fullPath.startsWith(SMPOpenActivity.rootPath)
                    ? fullPath.substring(SMPOpenActivity.rootPath.length())
                    : fullPath;
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Recent items")
                .setItems(recentTitles, (dlg, which) -> playNewPath(recents.get(which)))
                .create();
        dialog.getListView().setOnItemLongClickListener((parent, view, position, id) -> {
            if (position == 0) {
                // Can't delete currently playing item
                return true;
            }
            dialog.dismiss();
            new AlertDialog.Builder(this)
                    .setTitle("Remove from recent?")
                    .setMessage(recentTitles[position])
                    .setNegativeButton("Cancel", (dlg, which1) -> {})
                    .setPositiveButton("Remove", (dlg, which1) -> {
                        recents.remove(position);
                        recents.remove(0);
                        Collections.reverse(recents);
                        prefs.edit()
                                .putString("state_recentPaths", String.join(":", recents))
                                .apply();
                    })
                    .show();
            return true;

        });
        dialog.show();
    }
}
