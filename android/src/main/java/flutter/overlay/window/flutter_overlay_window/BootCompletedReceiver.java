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
                boolean restartOnBoot = AppPreferences.getBoolean(context, "restart_on_boot", false);
                if (restartOnBoot) {
                    Log.d(TAG, "Restart on boot is enabled");
                    
                    // Instead of directly starting the service, launch the main activity first
                    // so it can initialize the Flutter engine
                    Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        launchIntent.putExtra("start_overlay_after_boot", true);
                        context.startActivity(launchIntent);
                        Log.d(TAG, "Launched main activity to initialize Flutter engine");
                    } else {
                        Log.e(TAG, "Could not get launch intent for package");
                    }
                } else {
                    Log.d(TAG, "Restart on boot is disabled");
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to start overlay after boot: " + e.getMessage());
            }
        }
    }
} 
