package flutter.overlay.window.flutter_overlay_window;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
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

import androidx.annotation.RequiresApi;

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
    private static final String TAG = "OverlayService";
    public static final String INTENT_EXTRA_IS_CLOSE_WINDOW = "IsCloseWindow";
    private static final String WAKELOCK_TAG = "flutter_overlay_window:WakeLock";
    private static final float MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER = 0.8f;

    private static OverlayService instance;
    public static boolean isRunning = false;
    private WindowManager windowManager = null;
    private FlutterView flutterView;
    private MethodChannel flutterChannel;
    private BasicMessageChannel<Object> overlayMessageChannel;
    private int clickableFlag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

    private Handler mAnimationHandler = new Handler();
    private float lastX, lastY;
    private int lastYPosition;
    private boolean dragging;
    private Point szWindow = new Point();
    private Timer mTrayAnimationTimer;
    private PowerManager.WakeLock wakeLock;

    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    public void onAccessibilityEvent(AccessibilityEvent accessibilityEvent) {
        try {
            AccessibilityNodeInfo parentNodeInfo = accessibilityEvent.getSource();
            sendBroadcast(new Intent("accessibility_event").putExtra("SEND_BROADCAST", true));
        } catch (Exception ex) {
            logError("onAccessibilityEvent", ex);
        }
    }

    @Override
    public void onInterrupt() {
        // Service interrupted
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public void onDestroy() {
        logDebug("Destroying the overlay window service");
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
            logError("Error releasing wakelock", e);
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
                logError("Error removing view", e);
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
            logError("Error cancelling notification", e);
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
                    Log.e(TAG, "Error stopping service: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in removeOverlay: " + e.getMessage());
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
            if (engine == null || (flags & START_FLAG_REDELIVERY) != 0 && !isEngineValid(engine)) {
                logDebug("FlutterEngine unavailable or invalid after restart");
                stopSelf();
                if ((flags & START_FLAG_REDELIVERY) != 0) sendRestartBroadcast();
                return START_NOT_STICKY;
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
            logDebug("Service started");
            
            try {
                engine.getLifecycleChannel().appIsResumed();
            } catch (Exception e) {
                logError("Error resuming engine", e);
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
            logError("Fatal error in onStartCommand", e);
            cleanupAndStop();
            if (isAfterReboot(flags)) sendRestartBroadcast();
            return START_NOT_STICKY;
        }
    }

    private boolean shouldStopService(Intent intent) {
        return intent == null || (intent.getAction() != null && Intent.ACTION_SHUTDOWN.equals(intent.getAction()));
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
            logError("Error acquiring wakelock", e);
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
            
            setupMethodChannels(engine);
            
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (windowManager == null) {
                logError("Failed to get WindowManager", null);
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
                logError("Error adding view", e);
            }
        } catch (Exception e) {
            logError("Error in view setup", e);
        }
    }

    private void setupMethodChannels(FlutterEngine engine) {
        flutterChannel = new MethodChannel(engine.getDartExecutor(), OverlayConstants.OVERLAY_TAG);
        flutterChannel.setMethodCallHandler((call, result) -> {
            try {
                handleMethodCall(call, result);
            } catch (Exception e) {
                logError("Error in method call", e);
                result.error("METHOD_ERROR", e.getMessage(), null);
            }
        });
        
        overlayMessageChannel = new BasicMessageChannel<>(
                engine.getDartExecutor(), OverlayConstants.MESSENGER_TAG, JSONMessageCodec.INSTANCE);
        overlayMessageChannel.setMessageHandler((message, reply) -> {
            try {
                WindowSetup.messenger.send(message);
            } catch (Exception e) {
                logError("Error in message handler", e);
            }
        });
    }

    private void handleMethodCall(io.flutter.plugin.common.MethodCall call, MethodChannel.Result result) {
        if (windowManager == null || flutterView == null) {
            result.success(false);
            return;
        }
        
        try {
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
        } catch (Exception e) {
            logError("Error handling method: " + call.method, e);
            result.error("METHOD_ERROR", e.getMessage(), null);
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
            mStatusBarHeight = statusBarHeightId > 0 ? 
                mResources.getDimensionPixelSize(statusBarHeightId) : 
                dpToPx(DEFAULT_STATUS_BAR_HEIGHT_DP);
        }
        return mStatusBarHeight;
    }

    int navigationBarHeightPx() {
        if (mNavigationBarHeight == -1) {
            int navBarHeightId = mResources.getIdentifier("navigation_bar_height", "dimen", "android");
            mNavigationBarHeight = navBarHeightId > 0 ? 
                mResources.getDimensionPixelSize(navBarHeightId) : 
                dpToPx(DEFAULT_NAV_BAR_HEIGHT_DP);
        }
        return mNavigationBarHeight;
    }

    private void updateViewParams(WindowManager.LayoutParams params, Runnable action) {
        if (windowManager != null && flutterView != null && flutterView.getWindowToken() != null) {
            action.run();
            try {
                windowManager.updateViewLayout(flutterView, params);
            } catch (Exception e) {
                logError("Error updating view layout", e);
            }
        }
    }

    private void setBlurSettings(int blurRadius, MethodChannel.Result result) {
        if (windowManager != null && flutterView != null) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            updateViewParams(params, () -> {
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
            });
            result.success(true);
        } else {
            result.success(false);
        }
    }

    private void disableClickFlag(boolean enable, MethodChannel.Result result) {
        if (windowManager != null && flutterView != null) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            updateViewParams(params, () -> {
                int commonFlags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
                
                if (enable) {
                    params.flags = WindowManager.LayoutParams.FLAG_FULLSCREEN | commonFlags;
                    params.alpha = MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER;
                } else {
                    params.flags = WindowSetup.flag | commonFlags;
                    params.alpha = 1;
                }
            });
            result.success(true);
        } else {
            result.success(false);
        }
    }

    private void updateOverlayFlag(MethodChannel.Result result, String flag) {
        if (windowManager != null && flutterView != null) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            updateViewParams(params, () -> {
                WindowSetup.setFlag(flag);
                int commonFlags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR |
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
                        
                params.flags = WindowSetup.flag | commonFlags;
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && WindowSetup.flag == clickableFlag) {
                    params.alpha = MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER;
                } else {
                    params.alpha = 1;
                }
            });
            result.success(true);
        } else {
            result.success(false);
        }
    }

    private void resizeOverlay(int width, int height, boolean enableDrag, MethodChannel.Result result) {
        if (windowManager != null && flutterView != null) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            updateViewParams(params, () -> {
                params.width = (width == -1999 || width == -1) ? -1 : dpToPx(width);
                params.height = (height == -1999 || height == -1) ? height : dpToPx(height);
                WindowSetup.enableDrag = enableDrag;
            });
            result.success(true);
        } else {
            result.success(false);
        }
    }

    private void moveOverlay(int x, int y, MethodChannel.Result result) {
        try {
            if (windowManager != null && flutterView != null) {
                try {
                    WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
                    updateViewParams(params, () -> {
                        params.x = (x == -1999 || x == -1) ? -1 : dpToPx(x);
                        params.y = dpToPx(y);
                    });
                    if (result != null) result.success(true);
                } catch (IllegalArgumentException e) {
                    logError("Error moving overlay", e);
                    if (result != null) result.error("MOVE_ERROR", "View not attached to window", null);
                }
            } else if (result != null) {
                result.success(false);
            }
        } catch (Exception e) {
            logError("General error in moveOverlay", e);
            if (result != null) result.error("MOVE_ERROR", e.getMessage(), null);
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
                Log.e(TAG, "Error getting current position: " + e.getMessage());
            }
        }
        return null;
    }

    public static boolean moveOverlay(int x, int y) {
        if (instance == null || instance.flutterView == null || instance.windowManager == null) return false;
        
        try {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) instance.flutterView.getLayoutParams();
            instance.updateViewParams(params, () -> {
                params.x = (x == -1999 || x == -1) ? -1 : instance.dpToPx(x);
                params.y = instance.dpToPx(y);
            });
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error in static moveOverlay: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void onCreate() {
        try {
            createNotificationChannel();
            
            Intent notificationIntent = new Intent(this, FlutterOverlayWindowPlugin.class);
            int pendingFlags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ? 
                PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingFlags);
            
            final int notifyIcon = getDrawableResourceId("mipmap", "launcher");
            int iconToUse = notifyIcon == 0 ? R.drawable.notification_icon : notifyIcon;
            
            try {
                startForeground(OverlayConstants.NOTIFICATION_ID, 
                    new NotificationCompat.Builder(this, OverlayConstants.CHANNEL_ID)
                    .setContentTitle(WindowSetup.overlayTitle != null ? WindowSetup.overlayTitle : "Overlay Running")
                    .setContentText(WindowSetup.overlayContent != null ? WindowSetup.overlayContent : "Tap to return to app")
                    .setSmallIcon(iconToUse)
                    .setContentIntent(pendingIntent)
                    .setVisibility(WindowSetup.notificationVisibility)
                    .build());
            } catch (Exception e) {
                logError("Error creating notification", e);
                startForeground(OverlayConstants.NOTIFICATION_ID, 
                    new NotificationCompat.Builder(this, OverlayConstants.CHANNEL_ID)
                    .setContentTitle("Overlay Running")
                    .setContentText("Tap to return to app")
                    .setSmallIcon(iconToUse)
                    .build());
            }
            
            instance = this;
        } catch (Exception e) {
            logError("Error in onCreate", e);
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
        return getApplicationContext().getResources().getIdentifier(
            String.format("ic_%s", name), resType, getApplicationContext().getPackageName());
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
                    updateViewParams(params, () -> {
                        params.x += ((int) dx * (invertX ? -1 : 1));
                        params.y += ((int) dy * (invertY ? -1 : 1));
                    });
                    dragging = true;
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    lastYPosition = params.y;
                    if (!WindowSetup.positionGravity.equals("none")) {
                        updateViewParams(params, () -> {});
                        startTrayAnimation();
                    }
                    return false;
                default:
                    return false;
            }
            return false;
        } catch (Exception e) {
            logError("Error in onTouch", e);
            return false;
        }
    }

    private void startTrayAnimation() {
        try {
            if (mTrayAnimationTimer != null) {
                mTrayAnimationTimer.cancel();
                mTrayAnimationTimer = null;
            }
            
            mTrayAnimationTimer = new Timer();
            mTrayAnimationTimer.schedule(new TrayAnimationTimerTask(), 0, 25);
        } catch (Exception e) {
            logError("Error starting tray animation", e);
        }
    }

    private class TrayAnimationTimerTask extends TimerTask {
        int mDestX;
        int mDestY;
        WindowManager.LayoutParams params;

        public TrayAnimationTimerTask() {
            super();
            params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            mDestY = lastYPosition;
            
            switch (WindowSetup.positionGravity) {
                case "auto":
                    mDestX = (params.x + (flutterView.getWidth() / 2)) <= szWindow.x / 2 ? 0
                            : szWindow.x - flutterView.getWidth();
                    break;
                case "left":
                    mDestX = 0;
                    break;
                case "right":
                    mDestX = szWindow.x - flutterView.getWidth();
                    break;
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
                updateViewLayoutSafely(params);
                
                if (Math.abs(params.x - mDestX) < 2 && Math.abs(params.y - mDestY) < 2) {
                    cancel();
                    mTrayAnimationTimer.cancel();
                }
            });
        }
    }

    private void updateViewLayoutSafely(WindowManager.LayoutParams params) {
        if (windowManager != null && flutterView != null && flutterView.getWindowToken() != null) {
            try {
                windowManager.updateViewLayout(flutterView, params);
            } catch (Exception e) {
                logError("Error updating view layout", e);
            }
        }
    }

    private boolean isAfterReboot(int flags) {
        return (flags & START_FLAG_REDELIVERY) != 0;
    }

    private boolean isEngineValid(FlutterEngine engine) {
        try {
            return engine.getDartExecutor() != null && engine.getRenderer() != null;
        } catch (Exception e) {
            logError("Engine validation failed", e);
            return false;
        }
    }

    private void sendRestartBroadcast() {
        try {
            Intent intent = new Intent("flutter.overlay.window.RESTART_REQUIRED");
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
            logDebug("Sent restart broadcast");
        } catch (Exception e) {
            logError("Error sending restart broadcast", e);
        }
    }

    private void setupWindowSize() {
        try {
            if (windowManager != null) {
                windowManager.getDefaultDisplay().getSize(szWindow);
                return;
            }
            
            DisplayMetrics metrics = new DisplayMetrics();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                getDisplay().getRealMetrics(metrics);
            } else {
                WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) {
                    wm.getDefaultDisplay().getRealMetrics(metrics);
                } else {
                    metrics = getResources().getDisplayMetrics();
                }
            }
            szWindow.set(metrics.widthPixels, metrics.heightPixels);
        } catch (Exception e) {
            logError("Error setting up window size", e);
            szWindow.set(1080, 1920); // Fallback size
        }
    }

    private WindowManager.LayoutParams createLayoutParams() {
        int baseFlags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR |
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
                
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowSetup.flag | baseFlags,
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
            logError("Error setting layout params", e);
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.alpha = 1.0f;
        }
        
        return params;
    }

    private void logDebug(String message) {
        Log.d(TAG, message);
    }

    private void logError(String message, Exception e) {
        Log.e(TAG, message + (e != null ? ": " + e.getMessage() : ""));
    }
}
