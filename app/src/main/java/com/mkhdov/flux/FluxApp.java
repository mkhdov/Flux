package com.mkhdov.flux;

import android.app.Application;
import android.content.Context;

import com.mkhdov.flux.data.local.AppDatabase;
import com.mkhdov.flux.data.repo.AuthManager;
import com.mkhdov.flux.data.repo.CalendarRepository;
import com.mkhdov.flux.notifications.NotificationHelper;
import com.mkhdov.flux.notifications.NotificationScheduler;
import com.mkhdov.flux.notifications.PushManager;
import com.mkhdov.flux.sync.SyncEngine;
import com.mkhdov.flux.widget.WidgetUpdateManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FluxApp extends Application {
    private static FluxApp instance;
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationHelper.createChannels(this);
        PushManager.initialize(this);
        AppDatabase.get(this);
        AuthManager auth = AuthManager.get(this);
        CalendarRepository repository = CalendarRepository.get(this);
        SyncEngine sync = SyncEngine.get(this);
        sync.schedulePeriodic();
        IO.execute(() -> {
            repository.seedIfNeeded();
            repository.claimLocalDataIfNeeded();
            NotificationScheduler.rescheduleSync(this);
            WidgetUpdateManager.requestUpdate(this);
            if (auth.hasSession()) sync.requestSync("app start");
        });
    }

    public static FluxApp get() { return instance; }

    public static Context context() { return instance.getApplicationContext(); }

    public static ExecutorService io() { return IO; }
}
