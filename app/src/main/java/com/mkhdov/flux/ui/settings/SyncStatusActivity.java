package com.mkhdov.flux.ui.settings;

import com.mkhdov.flux.ui.common.BaseActivity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.mkhdov.flux.R;
import com.mkhdov.flux.data.repo.AuthManager;
import com.mkhdov.flux.sync.SyncEngine;
import com.mkhdov.flux.sync.SyncStatus;
import com.mkhdov.flux.ui.auth.LoginActivity;
import com.mkhdov.flux.ui.common.UiUtils;

import java.text.DateFormat;
import java.util.Date;

public class SyncStatusActivity extends BaseActivity {
    private AuthManager auth;
    private SyncEngine sync;
    private TextView account;
    private TextView accountSub;
    private TextView statePill;
    private TextView lastValue;
    private TextView pendingValue;
    private TextView conflictValue;
    private TextView deviceValue;
    private TextView note;
    private View signOut;
    private View signIn;
    private View resolve;

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sync_status);
        auth = AuthManager.get(this);
        sync = SyncEngine.get(this);
        account = findViewById(R.id.sync_account);
        accountSub = findViewById(R.id.sync_account_sub);
        statePill = findViewById(R.id.sync_state_pill);
        lastValue = findViewById(R.id.sync_last_value);
        pendingValue = findViewById(R.id.sync_pending_value);
        conflictValue = findViewById(R.id.sync_conflict_value);
        deviceValue = findViewById(R.id.sync_device_value);
        note = findViewById(R.id.sync_note);
        signOut = findViewById(R.id.sync_sign_out);
        signIn = findViewById(R.id.sync_sign_in);
        resolve = findViewById(R.id.sync_resolve);
        findViewById(R.id.sync_back).setOnClickListener(v -> finish());
        findViewById(R.id.sync_now).setOnClickListener(v -> {
            if (!auth.hasSession()) {
                UiUtils.snack(note, getString(R.string.sign_in_required));
                return;
            }
            sync.requestSync("manual sync");
            UiUtils.snack(note, getString(R.string.sync_now_started));
        });
        resolve.setOnClickListener(v -> resolveConflicts());
        signOut.setOnClickListener(v -> confirmSignOut());
        signIn.setOnClickListener(v -> startActivity(new Intent(this, LoginActivity.class)));
        sync.status().observe(this, this::render);
        render(sync.status().getValue());
        com.mkhdov.flux.data.repo.CalendarRepository.get(this).executor().execute(() -> {
            String id = sync.deviceId();
            runOnUiThread(() -> deviceValue.setText(id));
        });
    }

    private void render(SyncStatus status) {
        if (status == null) return;
        boolean signedIn = auth.hasSession();
        String email = auth.email();
        account.setText(signedIn && email != null ? email : getString(R.string.not_signed_in));
        accountSub.setText(signedIn ? getString(R.string.sync_offline_note) : getString(R.string.local_only_note));
        statePill.setText(label(status));
        int color = getColor(status.state == SyncStatus.State.NEEDS_ATTENTION ? R.color.accent : R.color.text_secondary);
        statePill.setTextColor(color);
        lastValue.setText(status.lastSync <= 0 ? getString(R.string.never_synced)
                : getString(R.string.last_sync_value, DateFormat.getDateTimeInstance(DateFormat.MEDIUM,
                DateFormat.SHORT).format(new Date(status.lastSync))));
        pendingValue.setText(String.valueOf(status.pending));
        conflictValue.setText(status.conflicts == 0 ? getString(R.string.no_conflicts)
                : getString(R.string.conflict_count, status.conflicts));
        conflictValue.setTextColor(status.conflicts > 0 ? getColor(R.color.accent) : getColor(R.color.text_primary));
        resolve.setVisibility(status.conflicts > 0 ? View.VISIBLE : View.GONE);
        signIn.setVisibility(signedIn ? View.GONE : View.VISIBLE);
        signOut.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        note.setText(auth.isLocalOnly() ? getString(R.string.local_only_note)
                : status.state == SyncStatus.State.NEEDS_ATTENTION ? getString(R.string.sync_conflict_note)
                : getString(R.string.sync_offline_note));
    }

    private String label(SyncStatus status) {
        switch (status.state) {
            case SYNCING: return getString(R.string.syncing);
            case SYNCED: return getString(R.string.synced);
            case OFFLINE: return getString(R.string.offline);
            case NEEDS_ATTENTION: return getString(R.string.needs_attention);
            case SIGNED_OUT: return getString(R.string.signed_out);
            case LOCAL_ONLY:
            default: return getString(R.string.local_only);
        }
    }

    private void resolveConflicts() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.resolve_conflicts)
                .setMessage(R.string.conflict_notice)
                .setPositiveButton(R.string.save_changes, (dialog, which) -> {
                    sync.resolveConflictsKeepLocal();
                    sync.syncNow("resolve conflicts");
                    UiUtils.snack(note, getString(R.string.sync_now_started));
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void confirmSignOut() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sign_out)
                .setMessage(R.string.sign_out_confirm)
                .setPositiveButton(R.string.sign_out, (dialog, which) -> {
                    auth.logout(false);
                    auth.setLocalOnly(true);
                    UiUtils.snack(note, getString(R.string.signed_out));
                    finish();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
