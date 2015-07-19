package landau.FMP;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

public class FMPMediaButtonReceiver extends BroadcastReceiver {
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

            FMPService.MediaButtonCommand command = FMPService.MediaButtonCommand.NOOP;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_HEADSETHOOK:
                    command = FMPService.MediaButtonCommand.NEXT;
                    break;

                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    command = FMPService.MediaButtonCommand.PLAY_PAUSE;
                    break;

                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    command = FMPService.MediaButtonCommand.NEXT;
                    break;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    command = FMPService.MediaButtonCommand.PREV;
                    break;

                case KeyEvent.KEYCODE_MEDIA_STOP:
                    command = FMPService.MediaButtonCommand.STOP;
                    break;

                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    command = FMPService.MediaButtonCommand.FAST_FORWARD;
                    break;
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                    command = FMPService.MediaButtonCommand.REWIND;
                    break;
            }

            if (command != FMPService.MediaButtonCommand.NOOP) {
                Intent i = new Intent(context, FMPService.class);
                i.setAction(FMPService.MEDIA_BUTTON_ACTION);
                i.putExtra(FMPService.MEDIA_BUTTON_COMMAND, command.name());
                context.startService(i);
                if (isOrderedBroadcast()) {
                    abortBroadcast();
                }
            }
        }
    }
}
