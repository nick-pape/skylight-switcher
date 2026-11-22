package house.pape.switcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Only for the first start (an app that was never opened gets no boot broadcast). */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        startForegroundService(new Intent(this, EdgeService.class));
        finish();
    }
}
