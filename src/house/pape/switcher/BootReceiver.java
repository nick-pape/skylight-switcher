package house.pape.switcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Puts the edge strips back after a reboot or an update. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        context.startForegroundService(new Intent(context, EdgeService.class));
    }
}
