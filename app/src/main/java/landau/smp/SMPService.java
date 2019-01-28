package landau.smp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.RemoteControlClient;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Log;

import java.io.IOException;
import java.util.List;

public class SMPService extends Service {
    public static final String MEDIA_BUTTON_ACTION = SMPService.class.getCanonicalName() + ".action";
    public static final String MEDIA_BUTTON_COMMAND = SMPService.class.getCanonicalName() + ".command";

    public enum MediaButtonCommand { NOOP, PLAY_PAUSE, NEXT, PREV, STOP, FAST_FORWARD, REWIND }

    private static final String TAG = SMPService.class.getSimpleName();


    private List<Song> songList;
    private int currentSong;
    private MediaPlayer mediaPlayer;
    private Notification.Builder notificationBuilder;
    private SongChangeNotification songChangeNotification;
    private RemoteControlClient remoteControlClient;
    private SharedPreferences prefs;
    private long shutoffTimerEndTime;
    private Handler shutoffTimer = new Handler();
    private Runnable shutoffTimerRunnable = () -> {
        shutoffTimerEndTime = 0;
        pause();
        Log.i(TAG, "Auto-paused");
    };

    public enum State {INVALID, PLAYING, PAUSED, STOPPED}
    private State state = State.INVALID;

    private AudioManager.OnAudioFocusChangeListener audioFocusListener = new AudioManager.OnAudioFocusChangeListener() {
        private static final int UNKNOWN = 0;
        private int prevFocusState = UNKNOWN;
        private State stateWhenLostFocus = State.INVALID;
        @Override
        public void onAudioFocusChange(int focusChange) {
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_GAIN:
                    Log.i(TAG, "Gained focus");
                    switch (prevFocusState) {
                        case UNKNOWN:
                        case AudioManager.AUDIOFOCUS_LOSS:
                            if (stateWhenLostFocus == State.PLAYING) {
                                playAfterStop();
                            }
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                            if (stateWhenLostFocus == State.PLAYING) {
                                playAfterPause();
                            }
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                            mediaPlayer.setVolume(1.0f, 1.0f);
                            break;
                    }
                    break;
                case AudioManager.AUDIOFOCUS_LOSS:
                    Log.i(TAG, "Lost focus");
                    stateWhenLostFocus = getState();
                    stop();
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    Log.i(TAG, "Lost focus (transient)");
                    stateWhenLostFocus = getState();
                    pause();
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    Log.i(TAG, "Lost focus (can duck)");
                    if (mediaPlayer != null) {
                        float volume = Integer.valueOf(prefs.getString("pref_duckVolume", "50")) / 100.0f;
                        volume = (float) ((Math.exp(volume) - 1) / (Math.E - 1));
                        mediaPlayer.setVolume(volume, volume);
                    }
                    break;
                default:
                    Log.i(TAG, "Unknown focus state " + focusChange);
                    break;
            }
            prevFocusState = focusChange;
        }
    };

    public interface SongChangeNotification {
        void onNextSong(Song s);
        void onStateChanged(State state);
    }

    @SuppressWarnings("WeakerAccess")
    public class SMPServiceBinder extends Binder {
        SMPService getService() {
            return SMPService.this;
        }
    }

    private final SMPServiceBinder binder = new SMPServiceBinder();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !MEDIA_BUTTON_ACTION.equals(intent.getAction())) {
            return START_STICKY;
        }

        String cmd = intent.getStringExtra(MEDIA_BUTTON_COMMAND);
        MediaButtonCommand command = cmd == null ? MediaButtonCommand.NOOP : MediaButtonCommand.valueOf(cmd);

        switch (command) {
            case NOOP:
                break;
            case PLAY_PAUSE:
                playpause();
                break;
            case NEXT:
                next();
                break;
            case PREV:
                prev();
                break;
            case STOP:
                if (state == State.PLAYING) {
                    playpause();
                }
                break;
            case FAST_FORWARD:
                fastForward();
                break;
            case REWIND:
                rewind();
                break;
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return false;
    }

    public void init(List<Song> songList, SongChangeNotification songChangeNotification) {
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        setSongList(songList);
        Intent intent = new Intent(this, SMPActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("channel", "SMP", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            NotificationManager manager = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
            assert manager != null;
            manager.createNotificationChannel(channel);
            notificationBuilder = new Notification.Builder(this, "channel");
        } else {
            notificationBuilder = new Notification.Builder(this);
        }
        notificationBuilder
                .setContentIntent(PendingIntent.getActivity(this, 0, intent, 0))
                .setSmallIcon(android.R.drawable.ic_media_pause)
                .setContentTitle(getText(R.string.app_name));
        startForeground(1, notificationBuilder.build());
        this.songChangeNotification = songChangeNotification;
        if (songList.size() > 0) {
            Song song = songList.get(currentSong);
            song.extractMetadata();
            playAfterStop();
            pause();
            stopShutoffTimer();
            if (prefs.contains("state_lastPosition")) {
                int position = prefs.getInt("state_lastPosition", 0);
                seek(position);
            }
            songChangeNotification.onNextSong(song);
        }
    }

    public void  setSongList(List<Song> songList) {
        stop();
        this.songList = songList;
        currentSong = prefs.getInt("state_lastPlayedSong", 0);
        if (currentSong >= songList.size()) {
            currentSong = 0;
        }
    }

    public void connect(SongChangeNotification songChangeNotification) {
        this.songChangeNotification = songChangeNotification;
        if (currentSong < songList.size()) {
            songChangeNotification.onNextSong(songList.get(currentSong));
        } else {
            songChangeNotification.onNextSong(null);
        }
        songChangeNotification.onStateChanged(state);
    }

    public void deinit() {
        stopForeground(true);
        stop();
        updateState(State.INVALID);

        AudioManager audioManager = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
        assert audioManager != null;
        audioManager.unregisterRemoteControlClient(remoteControlClient);
        audioManager.unregisterMediaButtonEventReceiver(new ComponentName(this, SMPMediaButtonReceiver.class));

        songChangeNotification = null;
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }

        prefs.edit().putInt("state_lastPlayedSong", currentSong).apply();

        stopSelf();
    }

    private void pause() {
        if (mediaPlayer == null || state != State.PLAYING) {
            return;
        }
        setPauseNotificationIcon();
        mediaPlayer.pause();
        updateState(State.PAUSED);
    }

    private void playCommon() {
        setNotification();
        mediaPlayer.setVolume(1.0f, 1.0f);
        mediaPlayer.start();
        maybeStartShutoffTimer();
        updateState(State.PLAYING);
    }

    private void playAfterPause() {
        if (mediaPlayer == null || state != State.PAUSED) {
            return;
        }
        playCommon();
    }

    private void playAfterStop() {
        if (state != State.STOPPED) {
            return;
        }
        if (mediaPlayer == null) {
            if (songList.size() > 0) {
                mediaPlayer = new MediaPlayer();
                initMediaPlayer(mediaPlayer, songList.get(currentSong).getFilename());
            } else {
                // no songs
                return;
            }
        }

        AudioManager am = (AudioManager)getSystemService(AUDIO_SERVICE);
        assert am != null;
        int result = am.requestAudioFocus(audioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.e(TAG, "Can't get focus: " + result);
            return;
        }
        registerRemoteControl();
        playCommon();
    }

    private void stop() {
        stopShutoffTimer();
        saveCurrentPosition();
        updateState(State.STOPPED);
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    public void playpause() {
        if (state == State.PLAYING) {
            pause();
        } else if (state == State.PAUSED) {
            playAfterPause();
        }  else if (state == State.STOPPED) {
            playAfterStop();
        } else if (state == State.INVALID) {
            Log.e(TAG, "playpause() called with INVALID state");
        }
    }

    public void prev() {
        if (mediaPlayer != null) {
            currentSong = (currentSong - 2 + songList.size()) % songList.size();
            next();
        }
    }

    public void next() {
        if (mediaPlayer != null) {
            mediaPlayer.seekTo(mediaPlayer.getDuration());
            if (state == State.PAUSED) {
                playpause();
            }
        }
    }

    public void seek(int time) {
        if (mediaPlayer != null) {
            mediaPlayer.seekTo(time);
        }
    }

    public void rewind() {
        int newtime = getCurrentTime() - 10000;
        if (newtime < 0) {
            newtime = 0;
        }
        seek(newtime);
    }

    public void fastForward() {
        int newtime = getCurrentTime() + 10000;
        if (newtime > getDuration()) {
            newtime = getDuration();
        }
        seek(newtime);
    }

    public void removeSongChangeNotification() {
        songChangeNotification = null;
    }

    public State getState() {
        return state;
    }

    public int getCurrentTime() {
        return mediaPlayer != null ? mediaPlayer.getCurrentPosition() : 0;
    }

    public int getDuration() {
        return mediaPlayer != null ? mediaPlayer.getDuration() : 0;
    }

    private void updateState(State newState) {
        state = newState;
        if (songChangeNotification != null) {
            songChangeNotification.onStateChanged(state);
        }
        if (remoteControlClient == null) {
            return;
        }
        switch (state) {
            case PLAYING:
                remoteControlClient.setPlaybackState(RemoteControlClient.PLAYSTATE_PLAYING);
                break;
            case PAUSED:
                remoteControlClient.setPlaybackState(RemoteControlClient.PLAYSTATE_PAUSED);
                break;
            case STOPPED:
                remoteControlClient.setPlaybackState(RemoteControlClient.PLAYSTATE_STOPPED);
        }
    }

    private void setPauseNotificationIcon() {
        notificationBuilder.setSmallIcon(android.R.drawable.ic_media_pause);
        NotificationManager notificationManager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        assert notificationManager != null;
        notificationManager.notify(1, notificationBuilder.build());
    }

    private void setNotification() {
        Song song = songList.get(currentSong);
        song.extractMetadata();
        if (songChangeNotification != null) {
            songChangeNotification.onNextSong(song);
        }

        RemoteControlClient.MetadataEditor e = remoteControlClient.editMetadata(true);
        e.putString(MediaMetadataRetriever.METADATA_KEY_TITLE, MetadataUtils.getTitleRCC(prefs, song));
        String s = MetadataUtils.getArtistRCC(prefs, song);
        e.putString(MediaMetadataRetriever.METADATA_KEY_ARTIST, s);
        e.putString(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST, s);
        e.putString(MediaMetadataRetriever.METADATA_KEY_ALBUM, MetadataUtils.getAlbumRCC(prefs, song));
        e.putLong(MediaMetadataRetriever.METADATA_KEY_DURATION, song.getDuration());
        e.apply();

        notificationBuilder.setContentTitle(MetadataUtils.getTitle(prefs, song))
                .setContentText(MetadataUtils.getArtistAndAlbum(prefs, song))
                .setContentInfo(MetadataUtils.formatTime(song.getDuration()))
                .setSmallIcon(android.R.drawable.ic_media_play);
        NotificationManager notificationManager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        assert notificationManager != null;
        notificationManager.notify(1, notificationBuilder.build());
    }

    private void initMediaPlayer(MediaPlayer mediaPlayer, String filename) {
        mediaPlayer.reset();
        try {
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mediaPlayer.setDataSource(filename);
            mediaPlayer.setOnCompletionListener(mp -> {
                String key = getFileKey(filename);
                if (prefs.contains(key)) {
                    prefs.edit().remove(key).apply();
                }
                currentSong = (currentSong + 1) % songList.size();
                setNotification();
                initMediaPlayer(mp, songList.get(currentSong).getFilename());
                mp.start();
            });
            mediaPlayer.prepare();
            int pos = prefs.getInt(getFileKey(filename), -1);
            if (pos != -1) {
                mediaPlayer.seekTo(pos);
            }
            mediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        } catch (IOException e) {
            Log.e(TAG, "setDataSource failed, path=" + filename, e);
        }
    }

    private void registerRemoteControl() {
        ComponentName eventReceiver = new ComponentName(this, SMPMediaButtonReceiver.class);
        AudioManager audioManager = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
        assert audioManager != null;
        audioManager.registerMediaButtonEventReceiver(eventReceiver);

        // build the PendingIntent for the remote control client
        Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        mediaButtonIntent.setComponent(eventReceiver);
        PendingIntent mediaPendingIntent = PendingIntent.getBroadcast(getApplicationContext(), 0, mediaButtonIntent, 0);
        // create and register the remote control client
        remoteControlClient = new RemoteControlClient(mediaPendingIntent);
        audioManager.registerRemoteControlClient(remoteControlClient);
        remoteControlClient.setTransportControlFlags(
                RemoteControlClient.FLAG_KEY_MEDIA_NEXT |
                RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS |
                RemoteControlClient.FLAG_KEY_MEDIA_PLAY |
                RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE |
                RemoteControlClient.FLAG_KEY_MEDIA_PAUSE |
                RemoteControlClient.FLAG_KEY_MEDIA_STOP |
                RemoteControlClient.FLAG_KEY_MEDIA_FAST_FORWARD |
                RemoteControlClient.FLAG_KEY_MEDIA_REWIND
        );
    }

    private void saveCurrentPosition() {
        String isSave = prefs.getString("pref_rememberPosition", "");
        if (isSave.isEmpty()) {
            prefs.edit().remove("state_lastPosition").apply();
            return;
        }
        int minLength = Integer.valueOf(isSave);    // in seconds
        if (mediaPlayer == null) {
            return;
        }
        if (mediaPlayer.getDuration() >= minLength * 1000) {
            SharedPreferences.Editor editor = prefs.edit();
            int curPos = mediaPlayer.getCurrentPosition();
            editor.putInt("state_lastPosition", curPos);
            if (curPos >= 30*1000 && curPos <= mediaPlayer.getDuration() - 30*1000) {
                editor.putInt(getFileKey(songList.get(currentSong).getFilename()), curPos);
            } else {
                editor.remove(getFileKey(songList.get(currentSong).getFilename()));
            }
            editor.apply();
        } else {
            prefs.edit()
                    .remove("state_lastPosition")
                    .remove(getFileKey(songList.get(currentSong).getFilename()))
                    .apply();
        }
    }

    private static String getFileKey(String filename) {
        return "filepos_" + filename;
    }

    private void maybeStartShutoffTimer() {
        if (shutoffTimerEndTime != 0) {
            // Timer is already set, do nothing
            return;
        }
        String timeoutStr = prefs.getString("pref_shutoffTimer", "0");
        setShutoffDelayResourceStr(timeoutStr);
    }

    public void setShutoffDelayResourceStr(String timeoutStr) {
        long timeoutMins;
        try {
            timeoutMins = Long.parseLong(timeoutStr);
        } catch (NumberFormatException e) {
            Log.e(TAG, "Can't parse shutoff timer value " + timeoutStr, e);
            return;
        }

        long delay = timeoutMins * 60 * 1000;
        setShutoffDelayMs(delay);
    }

    private void stopShutoffTimer() {
        if (shutoffTimerEndTime == 0) {
            return;
        }
        Log.i(TAG, "Disabled auto-pause in " + (shutoffTimerEndTime - SystemClock.uptimeMillis()) + " ms");
        shutoffTimerEndTime = 0;
        shutoffTimer.removeCallbacks(shutoffTimerRunnable);
    }

    public long getShutoffDelayMs() {
        if (shutoffTimerEndTime == 0) {
            return -1;
        }
        return shutoffTimerEndTime - SystemClock.uptimeMillis();
    }

    public void setShutoffDelayMs(long delay) {
        if (delay == 0) {
            // 0 means no shutoff timer
            stopShutoffTimer();
            return;
        }
        shutoffTimerEndTime = SystemClock.uptimeMillis() + delay;
        shutoffTimer.removeCallbacks(shutoffTimerRunnable);
        shutoffTimer.postAtTime(shutoffTimerRunnable, shutoffTimerEndTime);
        Log.i(TAG, "Auto-pause in " + delay + " ms");
    }
}
