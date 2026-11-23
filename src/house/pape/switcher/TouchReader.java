package house.pape.switcher;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;

/**
 * Watches the touchscreen's raw input events for a two- or three-finger horizontal swipe.
 * Reading the event device doesn't take the touches away from the apps.
 *
 * This only works because the Skylight's /dev/input nodes are world-readable and SELinux
 * is permissive; on a normal phone an app can't open them.
 */
class TouchReader extends Thread {
    private static final String TAG = "SkylightSwitcher";
    private static final int EV_SYN = 0, EV_KEY = 1, EV_ABS = 3, BTN_TOUCH = 0x14a, SYN_REPORT = 0, SYN_MT_REPORT = 2,
            ABS_MT_SLOT = 0x2f, ABS_MT_POSITION_X = 0x35, ABS_MT_POSITION_Y = 0x36, ABS_MT_TRACKING_ID = 0x39;
    private static final int MAX_SLOTS = 10;

    interface Listener {
        void onSwipe(int fingers, int direction);
    }

    private final File device;
    private final Listener listener;
    /** Horizontal travel of the fingers' average position that counts as a swipe, in device units. */
    private final int swipe;

    // Protocol A (one SYN_MT_REPORT per contact, which the Skylight's goodix-ts uses) and
    // protocol B (slots) both end up as these per-frame contact arrays.
    private final int[] xs = new int[MAX_SLOTS], ys = new int[MAX_SLOTS];
    private final boolean[] down = new boolean[MAX_SLOTS];
    private int slot, frameContacts, x, y;
    private boolean slots, contact, released;

    // Current gesture.
    private boolean tracking, fired;
    private int fingers;
    private float startX, startY;

    TouchReader(File device, int swipe, Listener listener) {
        super("TouchReader");
        setDaemon(true);
        this.device = device;
        this.swipe = swipe;
        this.listener = listener;
    }

    /** The first event device that's a touchscreen (INPUT_PROP_DIRECT), or null. */
    static File findTouchscreen() {
        File[] nodes = new File("/sys/class/input").listFiles();
        if (nodes == null) return null;
        for (File n : nodes) {
            if (!n.getName().startsWith("event")) continue;
            try {
                String props = new String(Files.readAllBytes(new File(n, "device/properties").toPath())).trim();
                if ((Long.parseLong(props.split(" ")[props.split(" ").length - 1], 16) & 0x2) != 0) {
                    return new File("/dev/input", n.getName());
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }
        return null;
    }

    @Override
    public void run() {
        // struct input_event: struct timeval (two longs), u16 type, u16 code, s32 value.
        int size = android.os.Process.is64Bit() ? 24 : 16;
        byte[] buf = new byte[size * 64];
        try (InputStream in = new FileInputStream(device)) {
            Log.i(TAG, "reading " + device);
            while (!isInterrupted()) {
                int n = in.read(buf);
                if (n < 0) break;
                ByteBuffer b = ByteBuffer.wrap(buf, 0, n).order(ByteOrder.nativeOrder());
                for (int off = 0; off + size <= n; off += size) {
                    event(b.getShort(off + size - 8) & 0xffff, b.getShort(off + size - 6) & 0xffff, b.getInt(off + size - 4));
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "can't read " + device, e);
        }
    }

    private void event(int type, int code, int value) {
        if (type == EV_ABS) {
            switch (code) {
                case ABS_MT_SLOT:
                    slots = true;
                    slot = Math.max(0, Math.min(MAX_SLOTS - 1, value));
                    break;
                case ABS_MT_TRACKING_ID:
                    if (slots) down[slot] = value >= 0;
                    break;
                case ABS_MT_POSITION_X:
                    if (slots) xs[slot] = value; else x = value;
                    contact = true;
                    break;
                case ABS_MT_POSITION_Y:
                    if (slots) ys[slot] = value; else y = value;
                    contact = true;
                    break;
            }
        } else if (type == EV_KEY && code == BTN_TOUCH && value == 0) {
            released = true;
        } else if (type == EV_SYN && code == SYN_MT_REPORT) {
            // An empty report (no position since the last one) means no contacts.
            if (contact && frameContacts < MAX_SLOTS) {
                xs[frameContacts] = x;
                ys[frameContacts] = y;
                frameContacts++;
            }
            contact = false;
        } else if (type == EV_SYN && code == SYN_REPORT) {
            int count = 0;
            float sx = 0, sy = 0;
            for (int i = 0; i < (slots ? MAX_SLOTS : frameContacts); i++) {
                if (slots && !down[i]) continue;
                count++;
                sx += xs[i];
                sy += ys[i];
            }
            frameContacts = 0;
            if (released) {
                count = 0;
                released = false;
                java.util.Arrays.fill(down, false);
            }
            frame(count, count == 0 ? 0 : sx / count, count == 0 ? 0 : sy / count);
        }
    }

    private void frame(int count, float cx, float cy) {
        if (count == 0) {
            tracking = fired = false;
            return;
        }
        if (count < 2) return;
        if (!tracking || count != fingers) {
            // A new gesture, or a finger joined or left: measure from here.
            tracking = true;
            fingers = count;
            startX = cx;
            startY = cy;
            return;
        }
        float dx = cx - startX, dy = cy - startY;
        if (!fired && Math.abs(dx) > swipe && Math.abs(dx) > 2 * Math.abs(dy)) {
            fired = true;
            listener.onSwipe(count, dx > 0 ? 1 : -1);
        }
    }
}
