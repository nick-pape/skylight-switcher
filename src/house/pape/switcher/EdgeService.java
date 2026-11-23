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
 * back to two invisible edge strips that switch on an inward swipe. Either way it switches
 * to Home Assistant when the Skylight app is in front, otherwise back to the Skylight app
 * (the home screen).
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
        File touch = TouchReader.findTouchscreen();
        if (touch != null && touch.canRead()) {
            Handler main = new Handler(Looper.getMainLooper());
            reader = new TouchReader(touch, SWIPE_UNITS, (fingers, direction) -> main.post(() -> {
                Log.i(TAG, fingers + "-finger swipe " + (direction > 0 ? "right" : "left"));
                toggle(this);
            }));
            reader.start();
        } else {
            Log.w(TAG, "no readable touchscreen (" + touch + "); using edge strips");
            wm = getSystemService(WindowManager.class);
            left = strip(Gravity.LEFT, +1);
            right = strip(Gravity.RIGHT, -1);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (reader != null) reader.interrupt();
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
                            toggle(EdgeService.this);
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

    static void toggle(Context ctx) {
        String front = foreground(ctx);
        Log.i(TAG, "swipe; in front: " + front);
        Intent i;
        if (SKYLIGHT.equals(front) || front == null) {
            i = ctx.getPackageManager().getLaunchIntentForPackage(HA);
            if (i == null) {
                Log.w(TAG, HA + " is not installed");
                return;
            }
        } else {
            // The Skylight app is the home screen.
            i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        ctx.startActivity(i);
    }

    /** The package of the last activity brought to the front, from the usage events of the last week. */
    private static String foreground(Context ctx) {
        UsageStatsManager usm = ctx.getSystemService(UsageStatsManager.class);
        long now = System.currentTimeMillis();
        UsageEvents events = usm.queryEvents(now - 7 * 86_400_000L, now);
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
