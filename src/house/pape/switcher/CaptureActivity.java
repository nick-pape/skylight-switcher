package house.pape.switcher;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

/**
 * Asks for screen capture and hands the grant to EdgeService. With the PROJECT_MEDIA app op
 * set to allow (once, over adb) Android grants it without showing the consent dialog, so
 * this activity is never visible.
 */
public class CaptureActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(), 1);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (result == RESULT_OK && data != null) {
            startService(new Intent(this, EdgeService.class)
                    .putExtra(EdgeService.EXTRA_CAPTURE_RESULT, result)
                    .putExtra(EdgeService.EXTRA_CAPTURE_DATA, data));
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
