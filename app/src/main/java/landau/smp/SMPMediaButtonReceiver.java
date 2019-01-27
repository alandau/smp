package landau.smp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;

public class SMPMediaButtonReceiver extends BroadcastReceiver {
    private static final String TAG = SMPMediaButtonReceiver.class.getSimpleName();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
            KeyEvent e = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            if (e == null) {
                return;
            }
            if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() != 0) {
                return;
            }

            SMPService.MediaButtonCommand command = SMPService.MediaButtonCommand.NOOP;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_HEADSETHOOK:
                    command = SMPService.MediaButtonCommand.NEXT;
                    break;

                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    command = SMPService.MediaButtonCommand.PLAY_PAUSE;
                    break;

                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    command = SMPService.MediaButtonCommand.NEXT;
                    break;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    command = SMPService.MediaButtonCommand.PREV;
                    break;

                case KeyEvent.KEYCODE_MEDIA_STOP:
                    command = SMPService.MediaButtonCommand.STOP;
                    break;

                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    command = SMPService.MediaButtonCommand.FAST_FORWARD;
                    break;
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                    command = SMPService.MediaButtonCommand.REWIND;
                    break;
            }

            if (command != SMPService.MediaButtonCommand.NOOP) {
                Intent i = new Intent(context, SMPService.class);
                i.setAction(SMPService.MEDIA_BUTTON_ACTION);
                i.putExtra(SMPService.MEDIA_BUTTON_COMMAND, command.name());
                try {
                    context.startService(i);
                } catch (Exception ex) {
                    // Starting a service causes the following exception:
                    // java.lang.RuntimeException: Unable to start receiver landau.smp.SMPMediaButtonReceiver:
                    // java.lang.IllegalStateException: Not allowed to start service Intent ...: app is in background
                    // This is even though we call unregisterMediaButtonEventReceiver on service shutdown
                    Log.e(TAG, "Can't startService with command=" + command.name(), ex);
                }
                if (isOrderedBroadcast()) {
                    abortBroadcast();
                }
            }
        }
    }
}
