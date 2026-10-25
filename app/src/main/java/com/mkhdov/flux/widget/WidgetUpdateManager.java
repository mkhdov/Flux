package com.mkhdov.flux.widget;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.atomic.AtomicBoolean;

public final class WidgetUpdateManager {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean PENDING = new AtomicBoolean(false);

    private WidgetUpdateManager() { }

    public static void requestUpdate(Context context) {
        if (!PENDING.compareAndSet(false, true)) return;
        final Context app = context.getApplicationContext();
        MAIN.postDelayed(() -> {
            PENDING.set(false);
            try {
                Intent intent = new Intent(app, AgendaWidgetProvider.class)
                        .setAction(AgendaWidgetProvider.ACTION_REFRESH);
                app.sendBroadcast(intent);
            } catch (Exception ignored) { }
        }, 700);
    }
}
