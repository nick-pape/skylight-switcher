package house.pape.switcher;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Slides from one app to the other the way the fingers moved.
 *
 * Android 12 plays its own task transition when another app comes to the front (on the
 * Skylight the new app always slides in from the right), and an ordinary app can't change
 * or turn it off. So this mirrors the screen into an ImageReader, and on a switch covers
 * the screen with an overlay holding a snapshot of the current app and the last snapshot
 * of the target app, switches underneath, slides the snapshots across and then removes
 * the overlay. The first switch to each app has no snapshot yet and uses the system's
 * transition.
 */
class Slider {
    private static final String TAG = "SkylightSwitcher";
    /** The slide; then, once the target app is in front, time for the system's transition to finish under the overlay; and the longest to wait for the app. */
    private static final int SLIDE_MS = 300, SETTLE_MS = 350, GIVE_UP_MS = 3000, POLL_MS = 50;

    private final Context ctx;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final int width, height;
    private final Map<String, Bitmap> snapshots = new HashMap<>();

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private Image latest;
    private FrameLayout overlay;

    Slider(Context ctx) {
        this.ctx = ctx;
        wm = ctx.getSystemService(WindowManager.class);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        width = dm.widthPixels;
        height = dm.heightPixels;
    }

    boolean capturing() {
        return projection != null;
    }

    void start(MediaProjection p) {
        stop();
        projection = p;
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.w(TAG, "screen capture stopped");
                projection = null;
                stop();
            }
        }, main);
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(r -> {
            Image img = r.acquireLatestImage();
            if (img == null) return;
            if (overlay != null) {
                img.close();   // a frame of our own overlay
                return;
            }
            if (latest != null) latest.close();
            latest = img;
        }, main);
        display = projection.createVirtualDisplay("skylight-switcher", width, height,
                ctx.getResources().getDisplayMetrics().densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, main);
        Log.i(TAG, "capturing the screen for the slide");
    }

    void stop() {
        if (display != null) display.release();
        if (latest != null) latest.close();
        if (reader != null) reader.close();
        MediaProjection p = projection;
        display = null;
        latest = null;
        reader = null;
        projection = null;
        if (p != null) p.stop();
    }

    /**
     * Switches from app `from` to app `to` by running `launch`, sliding in `direction`
     * (-1: the fingers moved left, so the new app comes in from the right). `arrived` says
     * whether `to` is in front yet; the overlay stays until it is.
     */
    void slide(String from, String to, int direction, Runnable launch, java.util.function.BooleanSupplier arrived) {
        if (overlay != null) return;   // still sliding
        Bitmap current = snapshot();
        if (current != null) snapshots.put(from, current);
        Bitmap next = snapshots.get(to);
        if (current == null || next == null) {
            launch.run();
            return;
        }
        overlay = new FrameLayout(ctx);
        ImageView out = image(current), in = image(next);
        in.setTranslationX(-direction * width);
        overlay.addView(out);
        overlay.addView(in);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE);
        lp.windowAnimations = 0;
        FrameLayout shown = overlay;
        wm.addView(shown, lp);
        // Once the overlay is on screen, switch underneath it and slide.
        main.postDelayed(() -> {
            launch.run();
            ValueAnimator a = ValueAnimator.ofFloat(0, 1).setDuration(SLIDE_MS);
            a.setInterpolator(new DecelerateInterpolator(1.5f));
            a.addUpdateListener(v -> {
                float t = (float) v.getAnimatedValue();
                out.setTranslationX(direction * width * t);
                in.setTranslationX(-direction * width * (1 - t));
            });
            a.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    removeWhen(shown, arrived, SystemClock.uptimeMillis() + GIVE_UP_MS);
                }
            });
            a.start();
        }, 32);
    }

    /** Removes the overlay once the target app is in front and its transition has played. */
    private void removeWhen(FrameLayout shown, java.util.function.BooleanSupplier arrived, long giveUp) {
        if (arrived.getAsBoolean() || SystemClock.uptimeMillis() > giveUp) {
            main.postDelayed(() -> {
                wm.removeView(shown);
                overlay = null;
            }, SETTLE_MS);
        } else {
            main.postDelayed(() -> removeWhen(shown, arrived, giveUp), POLL_MS);
        }
    }

    private ImageView image(Bitmap b) {
        ImageView v = new ImageView(ctx);
        v.setScaleType(ImageView.ScaleType.FIT_XY);
        v.setImageBitmap(b);
        v.setLayoutParams(new FrameLayout.LayoutParams(width, height));
        return v;
    }

    /** The screen as it is now, or null if not capturing. */
    private Bitmap snapshot() {
        if (latest == null) return null;
        Image.Plane plane = latest.getPlanes()[0];
        ByteBuffer buf = plane.getBuffer();
        buf.rewind();
        int stride = plane.getRowStride() / plane.getPixelStride();
        Bitmap padded = Bitmap.createBitmap(stride, height, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buf);
        return stride == width ? padded : Bitmap.createBitmap(padded, 0, 0, width, height);
    }
}
