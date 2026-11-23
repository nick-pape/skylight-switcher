package house.pape.switcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * The root of a task of our own that Home Assistant runs in, on top of this (invisible)
 * activity. Owning the task lets the switcher send it behind the Skylight app and bring it
 * back. The alternative, starting the Skylight app to go back to it, goes through its
 * launcher activity, a trampoline that restarts the calendar and takes about 1.5 s to draw.
 */
public class HostActivity extends Activity {
    /** The running host, or null. Only touched on the main thread. */
    private static HostActivity current;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        current = this;
        Intent ha = getPackageManager().getLaunchIntentForPackage(EdgeService.HA);
        if (ha == null) {
            finish();
            return;
        }
        // No FLAG_ACTIVITY_NEW_TASK: Home Assistant goes into this task, not its own.
        ha.setFlags(0);
        startActivity(ha);
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        if (current == this) current = null;
        super.onDestroy();
    }

    /** Sends Home Assistant (and this task) behind the Skylight app; false if there's no host. */
    static boolean sendToBack() {
        return current != null && !current.isFinishing() && current.moveTaskToBack(true);
    }

    /** Brings the Home Assistant task to the front; false if there's no host. */
    static boolean bringToFront() {
        if (current == null || current.isFinishing()) return false;
        android.app.ActivityManager am = current.getSystemService(android.app.ActivityManager.class);
        for (android.app.ActivityManager.AppTask t : am.getAppTasks()) {
            android.content.ComponentName base = t.getTaskInfo().baseActivity;
            if (base != null && base.getClassName().equals(HostActivity.class.getName())) {
                t.moveToFront();
                return true;
            }
        }
        return false;
    }
}
