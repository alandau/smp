package landau.smp;

import android.app.Notification;
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
import android.os.IBinder;
import android.os.PowerManager;
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
    private MediaPlayer mediaPlayer1, mediaPlayer2, mediaPlayer;
    private Notification.Builder notificationBuilder;
    private SongChangeNotification songChangeNotification;
    private RemoteControlClient remoteControlClient;
    private SharedPreferences prefs;

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
                    float volume = Integer.valueOf(prefs.getString("pref_duckVolume", "50")) / 100.0f;
                    volume = (float)((Math.exp(volume) - 1) / (Math.E - 1));
                    mediaPlayer.setVolume(volume, volume);
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
        notificationBuilder = new Notification.Builder(this)
                .setContentIntent(PendingIntent.getActivity(this, 0, intent, 0))
                .setSmallIcon(android.R.drawable.ic_media_pause)
                .setContentTitle(getText(R.string.app_name));
        startForeground(1, notificationBuilder.build());
        this.songChangeNotification = songChangeNotification;
        if (songList.size() > 0) {
            Song song = songList.get(currentSong);
            song.extractMetadata();
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
        audioManager.unregisterRemoteControlClient(remoteControlClient);
        audioManager.unregisterMediaButtonEventReceiver(new ComponentName(this, SMPMediaButtonReceiver.class));

        songChangeNotification = null;
        stopSelf();
    }

    private void initMediaPlayers() {
        if (songList.size() >= 1) {
            mediaPlayer1 = new MediaPlayer();
            initMediaPlayer(mediaPlayer1, songList.get(currentSong).getFilename());
            mediaPlayer = mediaPlayer1;

            // mediaPlayer2 might point to the same song if songList.size() == 1
            mediaPlayer2 = new MediaPlayer();
            prepareNextSong();
            initMediaPlayer(mediaPlayer2, songList.get((currentSong + 1) % songList.size()).getFilename());
            mediaPlayer1.setNextMediaPlayer(mediaPlayer2);
            mediaPlayer2.setNextMediaPlayer(mediaPlayer1);
        }
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
            initMediaPlayers();
        }
        if (mediaPlayer == null) {
            // no songs
            return;
        }
        AudioManager am = (AudioManager)getSystemService(AUDIO_SERVICE);
        int result = am.requestAudioFocus(audioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.e(TAG, "Can't get focus: " + result);
            return;
        }
        registerRemoteControl();
        playCommon();
    }

    private void stop() {
        if (mediaPlayer1 != null) {
            mediaPlayer1.release();
            mediaPlayer1 = null;
        }
        if (mediaPlayer2 != null) {
            mediaPlayer2.release();
            mediaPlayer2 = null;
        }
        mediaPlayer = null;
        updateState(State.STOPPED);
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
            prepareNextSong();
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

    private MediaPlayer otherMediaPlayer() {
        if (mediaPlayer == mediaPlayer1) {
            return mediaPlayer2;
        } else {
            return mediaPlayer1;
        }
    }

    private void setPauseNotificationIcon() {
        notificationBuilder.setSmallIcon(android.R.drawable.ic_media_pause);
        NotificationManager notificationManager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
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
        notificationManager.notify(1, notificationBuilder.build());
    }

    private void prepareNextSong() {
        prefs.edit().putInt("state_lastPlayedSong", currentSong).commit();
        int nextIdx = (currentSong + 1) % songList.size();
        if (nextIdx >= songList.size()) {
            return;
        }
        MediaPlayer other = otherMediaPlayer();
        initMediaPlayer(other, songList.get(nextIdx).getFilename());
        mediaPlayer.setNextMediaPlayer(other);
    }

    private void initMediaPlayer(MediaPlayer mediaPlayer, String filename) {
        mediaPlayer.reset();
        try {
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mediaPlayer.setDataSource(filename);
            mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    SMPService.this.mediaPlayer = otherMediaPlayer();
                    currentSong = (currentSong + 1) % songList.size();
                    setNotification();
                    prepareNextSong();
                }
            });
            mediaPlayer.prepare();
            mediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        } catch (IOException e) {
            Log.e(TAG, "setDataSource failed, path=" + filename, e);
        }
    }

    private void registerRemoteControl() {
        ComponentName eventReceiver = new ComponentName(this, SMPMediaButtonReceiver.class);
        AudioManager audioManager = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
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
}
