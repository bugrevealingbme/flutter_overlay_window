package flutter.overlay.window.flutter_overlay_window;

import static android.content.Context.MODE_PRIVATE;
import static flutter.overlay.window.flutter_overlay_window.OverlayConstants.*;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.HashMap;

import io.flutter.plugin.common.EventChannel;

public class AccessibilityReceiver extends BroadcastReceiver {

    private EventChannel.EventSink eventSink;

    public AccessibilityReceiver(EventChannel.EventSink eventSink) {
        this.eventSink = eventSink;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences sharedPreferences = context.getSharedPreferences(SHARED_PREFS_TAG, MODE_PRIVATE);
        String json = sharedPreferences.getString(ACCESSIBILITY_NODE, "");
        eventSink.success(json);
    }
}
