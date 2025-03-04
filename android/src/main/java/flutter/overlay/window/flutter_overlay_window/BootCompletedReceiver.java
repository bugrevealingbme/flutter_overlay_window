package flutter.overlay.window.flutter_overlay_window;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BootCompletedReceiver extends BroadcastReceiver {
    private static final String TAG = "BootCompletedReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d(TAG, "Boot completed, attempting to restart overlay");
            try {
                // Check if settings indicate overlay should be restarted on boot
                Log.d(TAG, "Restart on boot is enabled, starting service");
                Intent serviceIntent = new Intent(context, OverlayService.class);
                serviceIntent.putExtra("startFromBoot", true);
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to start overlay service after boot: " + e.getMessage());
            }
        }
    }
} 
