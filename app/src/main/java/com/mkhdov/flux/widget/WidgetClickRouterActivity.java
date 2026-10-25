package com.mkhdov.flux.widget;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.mkhdov.flux.sync.SyncEngine;
import com.mkhdov.flux.ui.event.EventEditorActivity;
import com.mkhdov.flux.ui.main.MainActivity;

import java.time.LocalDate;
import java.time.ZoneId;

public class WidgetClickRouterActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        if (intent.getBooleanExtra("refresh", false)) {
            SyncEngine.get(this).syncNow("widget refresh");
            WidgetUpdateManager.requestUpdate(this);
            finish();
            return;
        }
        long dateEpoch = intent.getLongExtra("date_epoch", 0);
        long occurrence = intent.getLongExtra("occurrence_start", 0);
        String eventId = intent.getStringExtra("event_id");
        String dateAction = intent.getStringExtra("date_action");
        if (intent.getBooleanExtra("new_event", false) || "new".equals(dateAction)) {
            Intent editor = new Intent(this, EventEditorActivity.class);
            editor.putExtra(EventEditorActivity.EXTRA_DATE, dateEpoch > 0 ? dateEpoch : System.currentTimeMillis());
            startActivity(editor);
        } else if (eventId != null) {
            Intent editor = new Intent(this, EventEditorActivity.class);
            editor.putExtra(EventEditorActivity.EXTRA_EVENT_ID, eventId);
            editor.putExtra(EventEditorActivity.EXTRA_OCCURRENCE_START, occurrence);
            startActivity(editor);
        } else {
            Intent main = new Intent(this, MainActivity.class)
                    .putExtra(MainActivity.EXTRA_SELECTED_DATE, dateEpoch > 0 ? dateEpoch : System.currentTimeMillis());
            main.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(main);
        }
        finish();
    }
}
