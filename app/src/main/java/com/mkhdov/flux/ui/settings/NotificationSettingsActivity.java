package com.mkhdov.flux.ui.settings;

import com.mkhdov.flux.ui.common.BaseActivity;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.mkhdov.flux.R;
import com.mkhdov.flux.notifications.NotificationHelper;
import com.mkhdov.flux.notifications.NotificationScheduler;
import com.mkhdov.flux.notifications.PushManager;
import com.mkhdov.flux.ui.common.UiUtils;

public class NotificationSettingsActivity extends BaseActivity {
    private TextView permissionStatus;
    private MaterialButton permissionButton;
    private TextView exactStatus;
    private MaterialButton exactButton;
    private TextView scheduledCount;
    private TextView pushStatus;
    private ActivityResultLauncher<String> permissionLauncher;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notification_settings);
        permissionStatus = findViewById(R.id.notification_permission_status);
        permissionButton = findViewById(R.id.notification_permission_button);
        exactStatus = findViewById(R.id.exact_alarm_status);
        exactButton = findViewById(R.id.exact_alarm_button);
        scheduledCount = findViewById(R.id.notification_scheduled_count);
        pushStatus = findViewById(R.id.notification_push_status);
        findViewById(R.id.notification_back).setOnClickListener(v -> finish());
        permissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            refresh();
            if (!granted) {
                UiUtils.snack(permissionStatus, getString(R.string.notification_permission_denied));
                openNotificationSettings();
            }
        });
        permissionButton.setOnClickListener(v -> {
            if (canPost()) {
                openNotificationSettings();
            } else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            } else {
                openNotificationSettings();
            }
        });
        exactButton.setOnClickListener(v -> openExactAlarmSettings());
        findViewById(R.id.notification_test).setOnClickListener(v -> {
            if (!canPost()) {
                UiUtils.snack(permissionStatus, getString(R.string.notification_permission_denied));
                return;
            }
            NotificationHelper.showTestNotification(this);
            UiUtils.snack(permissionStatus, getString(R.string.test_notification));
        });
        findViewById(R.id.notification_reschedule).setOnClickListener(v -> {
            NotificationScheduler.rescheduleSync(this);
            refresh();
            UiUtils.snack(scheduledCount, getString(R.string.reminders_rescheduled));
        });
        findViewById(R.id.notification_channel_button).setOnClickListener(v -> openNotificationSettings());
        refresh();
    }

    private void refresh() {
        boolean allowed = canPost();
        permissionStatus.setText(allowed ? R.string.notification_status_ok : R.string.notification_status_off);
        permissionStatus.setTextColor(getColor(allowed ? R.color.text_secondary : R.color.accent));
        permissionButton.setText(allowed ? R.string.open_settings : R.string.enable_notifications);
        boolean exact = NotificationScheduler.canScheduleExact(this);
        exactStatus.setText(exact ? R.string.exact_alarm_ok : R.string.exact_alarm_off);
        exactStatus.setTextColor(getColor(exact ? R.color.text_secondary : R.color.accent));
        exactButton.setVisibility(exact ? View.GONE : View.VISIBLE);
        try {
            scheduledCount.setText(getResources().getQuantityString(R.plurals.reminder_count,
                    NotificationScheduler.scheduledCount(this), NotificationScheduler.scheduledCount(this)));
        } catch (Exception e) {
            scheduledCount.setText(String.valueOf(NotificationScheduler.scheduledCount(this)));
        }
        pushStatus.setText(PushManager.isConfigured() ? R.string.push_configured : R.string.push_not_configured);
    }

    private boolean canPost() {
        return NotificationHelper.canPost(this);
    }

    private void openNotificationSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    private void openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < 31) { refresh(); return; }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            UiUtils.snack(exactStatus, getString(R.string.exact_alarm_permission_note));
        }
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }
}
