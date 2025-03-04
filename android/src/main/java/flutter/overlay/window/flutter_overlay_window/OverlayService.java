package flutter.overlay.window.flutter_overlay_window;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.app.PendingIntent;
import android.graphics.Point;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import io.flutter.embedding.android.FlutterTextureView;
import io.flutter.embedding.android.FlutterView;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterEngineCache;
import io.flutter.plugin.common.BasicMessageChannel;
import io.flutter.plugin.common.JSONMessageCodec;
import io.flutter.plugin.common.MethodChannel;

import android.os.PowerManager;

public class OverlayService extends AccessibilityService implements View.OnTouchListener {
    private final int DEFAULT_NAV_BAR_HEIGHT_DP = 48;
    private final int DEFAULT_STATUS_BAR_HEIGHT_DP = 25;

    private Integer mStatusBarHeight = -1;
    private Integer mNavigationBarHeight = -1;
    private Resources mResources;

    public static final String INTENT_EXTRA_IS_CLOSE_WINDOW = "IsCloseWindow";

    private static OverlayService instance;
    public static boolean isRunning = false;
    private WindowManager windowManager = null;
    private FlutterView flutterView;
    private MethodChannel flutterChannel = new MethodChannel(
            FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG).getDartExecutor(),
            OverlayConstants.OVERLAY_TAG);
    private BasicMessageChannel<Object> overlayMessageChannel = new BasicMessageChannel(
            FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG).getDartExecutor(),
            OverlayConstants.MESSENGER_TAG, JSONMessageCodec.INSTANCE);
    private int clickableFlag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

    private Handler mAnimationHandler = new Handler();
    private float lastX, lastY;
    private int lastYPosition;
    private boolean dragging;
    private static final float MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER = 0.8f;
    private Point szWindow = new Point();
    private Timer mTrayAnimationTimer;
    private TrayAnimationTimerTask mTrayTimerTask;

    private PowerManager.WakeLock wakeLock;
    private static final String WAKELOCK_TAG = "flutter_overlay_window:WakeLock";

    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    public void onAccessibilityEvent(AccessibilityEvent accessibilityEvent) {
        try {
            final int eventType = accessibilityEvent.getEventType();
            AccessibilityNodeInfo parentNodeInfo = accessibilityEvent.getSource();
            AccessibilityWindowInfo windowInfo = null;
            
            if (parentNodeInfo != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                windowInfo = parentNodeInfo.getWindow();
            }

            Intent intent = new Intent("accessibility_event");
            intent.putExtra("SEND_BROADCAST", true);
            sendBroadcast(intent);
        } catch (Exception ex) {
            Log.e("EVENT", "onAccessibilityEvent: " + ex.getMessage());
        }
    }

    @Override
    public void onInterrupt() {
        // Servis kesintiye uğradığında yapılacak işlemler.
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public void onDestroy() {
        Log.d("OverLay", "Destroying the overlay window service");
        
        releaseWakeLockSafely();
        removeViewSafely();
        
        isRunning = false;
        clearNotification();
        instance = null;
        
        super.onDestroy();
    }

    private void releaseWakeLockSafely() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                wakeLock = null;
            }
        } catch (Exception e) {
            Log.e("OverlayService", "Error releasing wakelock: " + e.getMessage());
        }
    }

    private void removeViewSafely() {
        if (windowManager != null && flutterView != null) {
            try {
                if (flutterView.getWindowToken() != null) {
                    windowManager.removeView(flutterView);
                    flutterView.detachFromFlutterEngine();
                }
            } catch (Exception e) {
                Log.e("OverlayService", "Error removing view: " + e.getMessage());
            } finally {
                windowManager = null;
                flutterView = null;
            }
        }
    }

    private void clearNotification() {
        try {
            NotificationManager notificationManager = (NotificationManager) getApplicationContext()
                    .getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                notificationManager.cancel(OverlayConstants.NOTIFICATION_ID);
            }
        } catch (Exception e) {
            Log.e("OverlayService", "Error cancelling notification: " + e.getMessage());
        }
    }

    public static void removeOverlay() {
        try {
            if (instance != null) {
                instance.removeViewSafely();
                instance.clearNotification();
                isRunning = false;
                
                try {
                    instance.stopSelf();
                } catch (Exception e) {
                    Log.e("OverlayService", "Error stopping service: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Log.e("OverlayService", "Error in removeOverlay: " + e.getMessage());
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            mResources = getApplicationContext().getResources();
            
            if (shouldStopService(intent)) {
                cleanupAndStop();
                return START_NOT_STICKY;
            }
            
            FlutterEngine engine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);
            if (engine == null) {
                Log.d("OverlayService", "FlutterEngine is null, cannot start service after device restart");
                
                if ((flags & START_FLAG_REDELIVERY) != 0) {
                    Log.d("OverlayService", "Service restarted after device reboot with null engine");
                    stopSelf();
                    sendRestartBroadcast();
                    return START_NOT_STICKY;
                }
                
                stopSelf();
                return START_NOT_STICKY;
            }
            
            if ((flags & START_FLAG_REDELIVERY) != 0) {
                Log.d("OverlayService", "Service restarted after device reboot");
                if (!isEngineValid(engine)) {
                    Log.d("OverlayService", "Engine exists but is in invalid state after reboot");
                    stopSelf();
                    sendRestartBroadcast();
                    return START_NOT_STICKY;
                }
            }
            
            boolean isCloseWindow = intent != null && intent.getBooleanExtra(INTENT_EXTRA_IS_CLOSE_WINDOW, false);
            if (isCloseWindow) {
                removeViewSafely();
                isRunning = false;
                stopSelf();
                return START_STICKY;
            }
            
            int startX = intent != null ? intent.getIntExtra("startX", OverlayConstants.DEFAULT_XY) : OverlayConstants.DEFAULT_XY;
            int startY = intent != null ? intent.getIntExtra("startY", OverlayConstants.DEFAULT_XY) : OverlayConstants.DEFAULT_XY;
            
            removeViewSafely();
            
            isRunning = true;
            Log.d("onStartCommand", "Service started");
            
            try {
                engine.getLifecycleChannel().appIsResumed();
            } catch (Exception e) {
                Log.e("OverlayService", "Error resuming engine: " + e.getMessage());
                if (isAfterReboot(flags)) {
                    stopSelf();
                    sendRestartBroadcast();
                    return START_NOT_STICKY;
                }
            }
            
            setupOverlayView(engine, startX, startY);
            
            acquireWakeLockSafely();
            
            return START_STICKY;
        } catch (Exception e) {
            Log.e("OverlayService", "Fatal error in onStartCommand: " + e.getMessage());
            cleanupAndStop();
            if (isAfterReboot(flags)) {
                sendRestartBroadcast();
            }
            return START_NOT_STICKY;
        }
    }

    private boolean shouldStopService(Intent intent) {
        return intent == null || 
               (intent.getAction() != null && Intent.ACTION_SHUTDOWN.equals(intent.getAction()));
    }

    private void cleanupAndStop() {
        releaseWakeLockSafely();
        removeViewSafely();
        isRunning = false;
        stopSelf();
    }

    private void acquireWakeLockSafely() {
        try {
            if (wakeLock == null) {
                PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (powerManager != null) {
                    wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);
                    wakeLock.setReferenceCounted(false);
                }
            }
            
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire();
            }
        } catch (Exception e) {
            Log.e("OverlayService", "Error acquiring wakelock: " + e.getMessage());
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    private void setupOverlayView(FlutterEngine engine, int startX, int startY) {
        try {
            flutterView = new FlutterView(getApplicationContext(), new FlutterTextureView(getApplicationContext()));
            flutterView.attachToFlutterEngine(engine);
            flutterView.setFitsSystemWindows(true);
            flutterView.setFocusable(true);
            flutterView.setFocusableInTouchMode(true);
            flutterView.setBackgroundColor(Color.TRANSPARENT);
            
            setupMethodChannels();
            
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (windowManager == null) {
                Log.e("OverlayService", "Failed to get WindowManager");
                return;
            }

            setupWindowSize();
            
            int dx = startX == OverlayConstants.DEFAULT_XY ? 0 : startX;
            int dy = startY == OverlayConstants.DEFAULT_XY ? -statusBarHeightPx() : startY;
            
            WindowManager.LayoutParams params = createLayoutParams();
            
            flutterView.setOnTouchListener(this);
            try {
                windowManager.addView(flutterView, params);
                moveOverlay(dx, dy, null);
            } catch (Exception e) {
                Log.e("OverlayService", "Error adding view: " + e.getMessage());
            }
        } catch (Exception e) {
            Log.e("OverlayService", "Error in view setup: " + e.getMessage());
        }
    }

    private void setupMethodChannels() {
        flutterChannel.setMethodCallHandler((call, result) -> {
            try {
                handleMethodCall(call, result);
            } catch (Exception e) {
                Log.e("OverlayService", "Error in method call: " + e.getMessage());
                result.error("METHOD_ERROR", e.getMessage(), null);
            }
        });
        
        overlayMessageChannel.setMessageHandler((message, reply) -> {
            try {
                WindowSetup.messenger.send(message);
            } catch (Exception e) {
                Log.e("OverlayService", "Error in message handler: " + e.getMessage());
            }
        });
    }

    private void handleMethodCall(io.flutter.plugin.common.MethodChannel.MethodCall call, MethodChannel.Result result) {
        switch (call.method) {
            case "disableClickFlag":
                boolean enableClick = call.argument("enableClick");
                disableClickFlag(enableClick, result);
                break;
            case "setBlurSettings":
                int blurRadius = call.argument("blurRadius");
                setBlurSettings(blurRadius, result);
                break;
            case "updateFlag":
                String flag = call.argument("flag").toString();
                updateOverlayFlag(result, flag);
                break;
            case "updateOverlayPosition":
                int x = call.<Integer>argument("x");
                int y = call.<Integer>argument("y");
                moveOverlay(x, y, result);
                break;
            case "resizeOverlay":
                int width = call.argument("width");
                int height = call.argument("height");
                boolean enableDrag = call.argument("enableDrag");
                resizeOverlay(width, height, enableDrag, result);
                break;
            default:
                result.notImplemented();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    private int screenHeight() {
        Display display = windowManager.getDefaultDisplay();
        DisplayMetrics dm = new DisplayMetrics();
        display.getRealMetrics(dm);
        return inPortrait() ? dm.heightPixels + statusBarHeightPx() + navigationBarHeightPx()
                : dm.heightPixels + statusBarHeightPx();
    }

    private int statusBarHeightPx() {
        if (mStatusBarHeight == -1) {
            int statusBarHeightId = mResources.getIdentifier("status_bar_height", "dimen", "android");

            if (statusBarHeightId > 0) {
                mStatusBarHeight = mResources.getDimensionPixelSize(statusBarHeightId);
            } else {
                mStatusBarHeight = dpToPx(DEFAULT_STATUS_BAR_HEIGHT_DP);
            }
        }

        return mStatusBarHeight;
    }

    int navigationBarHeightPx() {
        if (mNavigationBarHeight == -1) {
            int navBarHeightId = mResources.getIdentifier("navigation_bar_height", "dimen", "android");

            if (navBarHeightId > 0) {
                mNavigationBarHeight = mResources.getDimensionPixelSize(navBarHeightId);
            } else {
                mNavigationBarHeight = dpToPx(DEFAULT_NAV_BAR_HEIGHT_DP);
            }
        }

        return mNavigationBarHeight;
    }

    private void setBlurSettings(int blurRadius, MethodChannel.Result result) {
        if (windowManager != null && flutterView != null) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (blurRadius > 0) {
                    params.setBlurBehindRadius(blurRadius);
                    params.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
                    params.alpha = MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER;
                } else {
                    params.flags &= ~WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
                    params.alpha = 1;
                }
            }
            if (windowManager != null && flutterView != null && flutterView.getWindowToken() != null) {
                windowManager.updateViewLayout(flutterView, params);
            }
            result.success(true);
        } else {
            result.success(false);
        }
    }

    private void disableClickFlag(boolean enable, MethodChannel.Result result) {
        if (windowManager != null && flutterView != null) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            if (enable) {
                params.flags = WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
                params.alpha = MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER;
            } else {
                params.flags = WindowSetup.flag | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
                params.alpha = 1;
            }
            if (windowManager != null && flutterView != null && flutterView.getWindowToken() != null) {
                windowManager.updateViewLayout(flutterView, params);
            }
            result.success(true);
        } else {
            result.success(false);
        }
    }

    private void updateOverlayFlag(MethodChannel.Result result, String flag) {
        if (windowManager != null) {
            WindowSetup.setFlag(flag);
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            params.flags = WindowSetup.flag | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                    WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                    | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && WindowSetup.flag == clickableFlag) {
                params.alpha = MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER;
            } else {
                params.alpha = 1;
            }
            if (windowManager != null && flutterView != null && flutterView.getWindowToken() != null) {
                windowManager.updateViewLayout(flutterView, params);
            }
            result.success(true);
        } else {
            result.success(false);
        }
    }

    private void resizeOverlay(int width, int height, boolean enableDrag, MethodChannel.Result result) {
        if (windowManager != null) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            params.width = (width == -1999 || width == -1) ? -1 : dpToPx(width);
            params.height = (height == -1999 || height == -1) ? height : dpToPx(height);
            WindowSetup.enableDrag = enableDrag;
            if (windowManager != null && flutterView != null && flutterView.getWindowToken() != null) {
                windowManager.updateViewLayout(flutterView, params);
            }
            result.success(true);
        } else {
            result.success(false);
        }
    }

    private void moveOverlay(int x, int y, MethodChannel.Result result) {
        try {
            if (windowManager != null && flutterView != null) {
                try {
                    if (flutterView.getWindowToken() != null) {
                        WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
                        params.x = (x == -1999 || x == -1) ? -1 : dpToPx(x);
                        params.y = dpToPx(y);
                        windowManager.updateViewLayout(flutterView, params);
                        if (result != null)
                            result.success(true);
                    } else {
                        Log.w("OverlayService", "FlutterView not attached to window manager, skipping moveOverlay");
                        if (result != null)
                            result.success(false);
                    }
                } catch (IllegalArgumentException e) {
                    Log.e("OverlayService", "Error moving overlay: " + e.getMessage());
                    if (result != null)
                        result.error("MOVE_ERROR", "View not attached to window", null);
                }
            } else {
                if (result != null)
                    result.success(false);
            }
        } catch (Exception e) {
            Log.e("OverlayService", "General error in moveOverlay: " + e.getMessage());
            if (result != null)
                result.error("MOVE_ERROR", e.getMessage(), null);
        }
    }

    public static Map<String, Double> getCurrentPosition() {
        if (instance != null && instance.flutterView != null && instance.windowManager != null) {
            try {
                WindowManager.LayoutParams params = (WindowManager.LayoutParams) instance.flutterView.getLayoutParams();
                Map<String, Double> position = new HashMap<>();
                position.put("x", instance.pxToDp(params.x));
                position.put("y", instance.pxToDp(params.y));
                return position;
            } catch (Exception e) {
                Log.e("OverlayService", "Error getting current position: " + e.getMessage());
                return null;
            }
        }
        return null;
    }

    public static boolean moveOverlay(int x, int y) {
        try {
            if (instance != null && instance.flutterView != null) {
                if (instance.windowManager != null) {
                    try {
                        if (instance.flutterView.getWindowToken() != null) {
                            WindowManager.LayoutParams params = (WindowManager.LayoutParams) instance.flutterView.getLayoutParams();
                            params.x = (x == -1999 || x == -1) ? -1 : instance.dpToPx(x);
                            params.y = instance.dpToPx(y);
                            instance.windowManager.updateViewLayout(instance.flutterView, params);
                            return true;
                        } else {
                            Log.w("OverlayService", "FlutterView not attached in static moveOverlay");
                            return false;
                        }
                    } catch (IllegalArgumentException e) {
                        Log.e("OverlayService", "Error moving overlay (static): " + e.getMessage());
                        return false;
                    }
                } else {
                    return false;
                }
            } else {
                return false;
            }
        } catch (Exception e) {
            Log.e("OverlayService", "General error in static moveOverlay: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void onCreate() {
        try {
            createNotificationChannel();
            Intent notificationIntent = new Intent(this, FlutterOverlayWindowPlugin.class);
            int pendingFlags;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                pendingFlags = PendingIntent.FLAG_IMMUTABLE;
            } else {
                pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            }
            PendingIntent pendingIntent = PendingIntent.getActivity(this,
                    0, notificationIntent, pendingFlags);
            
            final int notifyIcon = getDrawableResourceId("mipmap", "launcher");
            int iconToUse = notifyIcon == 0 ? R.drawable.notification_icon : notifyIcon;
            
            try {
                Notification notification = new NotificationCompat.Builder(this, OverlayConstants.CHANNEL_ID)
                        .setContentTitle(WindowSetup.overlayTitle != null ? WindowSetup.overlayTitle : "Overlay Running")
                        .setContentText(WindowSetup.overlayContent != null ? WindowSetup.overlayContent : "Tap to return to app")
                        .setSmallIcon(iconToUse)
                        .setContentIntent(pendingIntent)
                        .setVisibility(WindowSetup.notificationVisibility)
                        .build();
                startForeground(OverlayConstants.NOTIFICATION_ID, notification);
            } catch (Exception e) {
                Log.e("OverlayService", "Error creating notification: " + e.getMessage());
                Notification fallbackNotification = new NotificationCompat.Builder(this, OverlayConstants.CHANNEL_ID)
                        .setContentTitle("Overlay Running")
                        .setContentText("Tap to return to app")
                        .setSmallIcon(iconToUse)
                        .build();
                startForeground(OverlayConstants.NOTIFICATION_ID, fallbackNotification);
            }
            
            instance = this;
        } catch (Exception e) {
            Log.e("OverlayService", "Error in onCreate: " + e.getMessage());
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    OverlayConstants.CHANNEL_ID,
                    "Foreground Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager manager = getSystemService(NotificationManager.class);
            assert manager != null;
            manager.createNotificationChannel(serviceChannel);
        }
    }

    private int getDrawableResourceId(String resType, String name) {
        return getApplicationContext().getResources().getIdentifier(String.format("ic_%s", name), resType,
                getApplicationContext().getPackageName());
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                Float.parseFloat(dp + ""), mResources.getDisplayMetrics());
    }

    private double pxToDp(int px) {
        return (double) px / mResources.getDisplayMetrics().density;
    }

    private boolean inPortrait() {
        return mResources.getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
    }

    @Override
    public boolean onTouch(View view, MotionEvent event) {
        if (windowManager == null || flutterView == null || !WindowSetup.enableDrag) {
            return false;
        }
        
        try {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = false;
                    lastX = event.getRawX();
                    lastY = event.getRawY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - lastX;
                    float dy = event.getRawY() - lastY;
                    if (!dragging && dx * dx + dy * dy < 25) {
                        return false;
                    }
                    lastX = event.getRawX();
                    lastY = event.getRawY();
                    boolean invertX = WindowSetup.gravity == (Gravity.TOP | Gravity.RIGHT)
                            || WindowSetup.gravity == (Gravity.CENTER | Gravity.RIGHT)
                            || WindowSetup.gravity == (Gravity.BOTTOM | Gravity.RIGHT);
                    boolean invertY = WindowSetup.gravity == (Gravity.BOTTOM | Gravity.LEFT)
                            || WindowSetup.gravity == Gravity.BOTTOM
                            || WindowSetup.gravity == (Gravity.BOTTOM | Gravity.RIGHT);
                    int xx = params.x + ((int) dx * (invertX ? -1 : 1));
                    int yy = params.y + ((int) dy * (invertY ? -1 : 1));
                    params.x = xx;
                    params.y = yy;
                    updateViewLayoutSafely(params);
                    dragging = true;
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    lastYPosition = params.y;
                    if (!WindowSetup.positionGravity.equals("none")) {
                        updateViewLayoutSafely(params);
                        startTrayAnimation();
                    }
                    return false;
                default:
                    return false;
            }
            return false;
        } catch (Exception e) {
            Log.e("OverlayService", "Error in onTouch: " + e.getMessage());
            return false;
        }
    }

    private void updateViewLayoutSafely(WindowManager.LayoutParams params) {
        if (windowManager != null && flutterView != null && flutterView.getWindowToken() != null) {
            try {
                windowManager.updateViewLayout(flutterView, params);
            } catch (Exception e) {
                Log.e("OverlayService", "Error updating view layout: " + e.getMessage());
            }
        }
    }

    private void startTrayAnimation() {
        try {
            if (mTrayAnimationTimer != null) {
                mTrayAnimationTimer.cancel();
                mTrayAnimationTimer = null;
            }
            
            mTrayTimerTask = new TrayAnimationTimerTask();
            mTrayAnimationTimer = new Timer();
            mTrayAnimationTimer.schedule(mTrayTimerTask, 0, 25);
        } catch (Exception e) {
            Log.e("OverlayService", "Error starting tray animation: " + e.getMessage());
        }
    }

    private class TrayAnimationTimerTask extends TimerTask {
        int mDestX;
        int mDestY;
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();

        public TrayAnimationTimerTask() {
            super();
            mDestY = lastYPosition;
            switch (WindowSetup.positionGravity) {
                case "auto":
                    mDestX = (params.x + (flutterView.getWidth() / 2)) <= szWindow.x / 2 ? 0
                            : szWindow.x - flutterView.getWidth();
                    return;
                case "left":
                    mDestX = 0;
                    return;
                case "right":
                    mDestX = szWindow.x - flutterView.getWidth();
                    return;
                default:
                    mDestX = params.x;
                    mDestY = params.y;
                    break;
            }
        }

        @Override
        public void run() {
            mAnimationHandler.post(() -> {
                params.x = (2 * (params.x - mDestX)) / 3 + mDestX;
                params.y = (2 * (params.y - mDestY)) / 3 + mDestY;
                if (windowManager != null && flutterView != null && flutterView.getWindowToken() != null) {
                    windowManager.updateViewLayout(flutterView, params);
                }
                if (Math.abs(params.x - mDestX) < 2 && Math.abs(params.y - mDestY) < 2) {
                    TrayAnimationTimerTask.this.cancel();
                    mTrayAnimationTimer.cancel();
                }
            });
        }
    }

    private boolean isAfterReboot(int flags) {
        return (flags & START_FLAG_REDELIVERY) != 0;
    }

    private boolean isEngineValid(FlutterEngine engine) {
        try {
            return engine.getDartExecutor() != null && 
                   engine.getRenderer() != null;
        } catch (Exception e) {
            Log.e("OverlayService", "Engine validation failed: " + e.getMessage());
            return false;
        }
    }

    private void sendRestartBroadcast() {
        try {
            Intent intent = new Intent("flutter.overlay.window.RESTART_REQUIRED");
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
            Log.d("OverlayService", "Sent restart broadcast");
        } catch (Exception e) {
            Log.e("OverlayService", "Error sending restart broadcast: " + e.getMessage());
        }
    }

    private void setupWindowSize() {
        try {
            if (windowManager != null) {
                windowManager.getDefaultDisplay().getSize(szWindow);
            } else {
                DisplayMetrics metrics = new DisplayMetrics();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    getDisplay().getRealMetrics(metrics);
                } else {
                    WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                    if (wm != null) {
                        wm.getDefaultDisplay().getRealMetrics(metrics);
                    } else {
                        DisplayMetrics defaultMetrics = getResources().getDisplayMetrics();
                        metrics = defaultMetrics;
                    }
                }
                szWindow.set(metrics.widthPixels, metrics.heightPixels);
            }
        } catch (Exception e) {
            Log.e("OverlayService", "Error setting up window size: " + e.getMessage());
            szWindow.set(1080, 1920);
        }
    }

    private WindowManager.LayoutParams createLayoutParams() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowSetup.flag | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        
        try {
            params.gravity = WindowSetup.gravity;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && 
                WindowSetup.flag == clickableFlag) {
                params.alpha = MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER;
            } else {
                params.alpha = 1.0f;
            }
        } catch (Exception e) {
            Log.e("OverlayService", "Error setting layout params: " + e.getMessage());
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.alpha = 1.0f;
        }
        
        return params;
    }

}
