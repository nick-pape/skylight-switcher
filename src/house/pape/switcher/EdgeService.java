package house.pape.switcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.io.File;

/**
 * Switches apps on a two- or three-finger horizontal swipe anywhere on screen, read from
 * the raw touchscreen events (see TouchReader). If the touchscreen can't be read, falls
 * back to two invisible edge strips that switch on an inward swipe. Swiping left goes to
 * Home Assistant and swiping right goes back to the Skylight app (the home screen); see
 * swipe(). Slider draws the slide.
 */
public class EdgeService extends Service {
    private static final String TAG = "SkylightSwitcher";
    static final String HA = "io.homeassistant.companion.android.minimal";
    static final String SKYLIGHT = "com.skylight";
    /** Strip width and the inward travel that counts as a swipe, in dp. */
    private static final int STRIP_DP = 16, SWIPE_DP = 60;

    /** Travel of a multi-finger swipe, in touchscreen units (about pixels on the Skylight). */
    private static final int SWIPE_UNITS = 250;

    private WindowManager wm;
    private View left, right;
    private TouchReader reader;
    private Slider slider;

    static final String EXTRA_CAPTURE_RESULT = "captureResult", EXTRA_CAPTURE_DATA = "captureData";

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("edge", "Edge swipe", NotificationManager.IMPORTANCE_MIN));
        startForeground(1, new Notification.Builder(this, "edge")
                .setContentTitle("Edge swipe to switch apps")
                .setSmallIcon(android.R.drawable.ic_menu_rotate)
                .setOngoing(true)
                .build());
        wm = getSystemService(WindowManager.class);
        slider = new Slider(this);
        File touch = TouchReader.findTouchscreen();
        if (touch != null && touch.canRead()) {
            Handler main = new Handler(Looper.getMainLooper());
            reader = new TouchReader(touch, SWIPE_UNITS, (fingers, direction) -> main.post(() -> {
                Log.i(TAG, fingers + "-finger swipe");
                swipe(direction);
            }));
            reader.start();
        } else {
            Log.w(TAG, "no readable touchscreen (" + touch + "); using edge strips");
            left = strip(Gravity.LEFT, +1);
            right = strip(Gravity.RIGHT, -1);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra(EXTRA_CAPTURE_DATA)) {
            MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
            slider.start(mpm.getMediaProjection(intent.getIntExtra(EXTRA_CAPTURE_RESULT, 0),
                    intent.getParcelableExtra(EXTRA_CAPTURE_DATA)));
        } else if (!slider.capturing()) {
            // Get screen capture for the slide (see Slider); CaptureActivity calls back here.
            startActivity(new Intent(this, CaptureActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (reader != null) reader.interrupt();
        slider.stop();
        for (View v : new View[] {left, right}) {
            if (v != null) wm.removeView(v);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** direction: +1 when swiping inward means moving right (left edge), -1 for the right edge. */
    private View strip(int gravity, int direction) {
        float density = getResources().getDisplayMetrics().density;
        float swipe = SWIPE_DP * density;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                (int) (STRIP_DP * density), WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = gravity | Gravity.TOP;
        View v = new View(this);
        v.setOnTouchListener(new View.OnTouchListener() {
            float startX;
            boolean fired;

            @Override
            public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = e.getRawX();
                        fired = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!fired && (e.getRawX() - startX) * direction > swipe) {
                            fired = true;
                            swipe(direction);
                        }
                        return true;
                    default:
                        return true;
                }
            }
        });
        wm.addView(v, lp);
        return v;
    }

    /**
     * The two apps sit side by side like Mac spaces: Skylight on the left, Home Assistant
     * on the right. Moving the fingers left (direction -1) goes to the app on the right,
     * moving them right goes back; there's no wrapping around past either end.
     */
    private void swipe(int direction) {
        String target = direction < 0 ? HA : SKYLIGHT;
        String front = foreground(this, 7 * 86_400_000L);
        if (front == null) front = SKYLIGHT;   // nothing recorded yet: assume the home screen
        Log.i(TAG, "swipe " + (direction < 0 ? "left" : "right") + "; in front: " + front);
        if (target.equals(front)) return;
        Intent i = getPackageManager().getLaunchIntentForPackage(target);
        if (i == null) {
            Log.w(TAG, target + " is not installed");
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        slider.slide(front, target, direction, () -> startActivity(i),
                () -> target.equals(foreground(this, 10_000)));
    }

    /** The package of the last activity brought to the front in the last `ms`, or null. */
    private static String foreground(Context ctx, long ms) {
        UsageStatsManager usm = ctx.getSystemService(UsageStatsManager.class);
        long now = System.currentTimeMillis();
        UsageEvents events = usm.queryEvents(now - ms, now);
        UsageEvents.Event e = new UsageEvents.Event();
        String last = null;
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND && !ctx.getPackageName().equals(e.getPackageName())) {
                last = e.getPackageName();
            }
        }
        return last;
    }
}
