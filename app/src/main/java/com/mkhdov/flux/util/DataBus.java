package com.mkhdov.flux.util;

import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class DataBus {
    public interface Listener { void onDataChanged(); }
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static long lastNotify;
    private static boolean pending;

    private DataBus() { }

    public static void add(Listener listener) {
        if (!LISTENERS.contains(listener)) LISTENERS.add(listener);
    }

    public static void remove(Listener listener) { LISTENERS.remove(listener); }

    public static void notifyChanged() {
        synchronized (DataBus.class) {
            long now = System.currentTimeMillis();
            if (now - lastNotify < 60) {
                if (pending) return;
                pending = true;
                MAIN.postDelayed(() -> { synchronized (DataBus.class) { pending = false; lastNotify = System.currentTimeMillis(); } dispatch(); }, 80);
                return;
            }
            lastNotify = now;
        }
        dispatch();
    }

    private static void dispatch() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            for (Listener l : LISTENERS) l.onDataChanged();
        } else {
            MAIN.post(() -> { for (Listener l : LISTENERS) l.onDataChanged(); });
        }
    }
}
