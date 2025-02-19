package flutter.overlay.window.flutter_overlay_window;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import flutter.overlay.window.flutter_overlay_window.OverlayService;


public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) || Intent.ACTION_REBOOT.equals(intent.getAction())) {
            Intent serviceIntent = new Intent(context, OverlayService.class);
            context.startService(serviceIntent);
        }
    }
}
