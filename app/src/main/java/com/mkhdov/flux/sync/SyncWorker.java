package com.mkhdov.flux.sync;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public class SyncWorker extends Worker {
    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull @Override public Result doWork() {
        try {
            SyncEngine.get(getApplicationContext()).performSyncBlocking("background");
            return Result.success();
        } catch (Exception e) {
            return Result.retry();
        }
    }
}
