package com.mkhdov.flux.notifications;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.mkhdov.flux.BuildConfig;

public final class PushManager {
    private static final String TAG = "PushManager";
    private static final String PREFS = "flux_push";
    private static volatile boolean initialized;

    private PushManager() { }

    public static boolean isConfigured() {
        return !isEmpty(BuildConfig.FIREBASE_APPLICATION_ID) && !isEmpty(BuildConfig.FIREBASE_API_KEY)
                && !isEmpty(BuildConfig.FIREBASE_PROJECT_ID) && !isEmpty(BuildConfig.FIREBASE_SENDER_ID);
    }

    public static void initialize(Context context) {
        if (initialized) return;
        initialized = true;
        if (!isConfigured()) {
            Log.i(TAG, "Firebase is not configured; server push disabled. Local reminders remain active.");
            return;
        }
        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseOptions options = new FirebaseOptions.Builder()
                        .setApplicationId(BuildConfig.FIREBASE_APPLICATION_ID)
                        .setApiKey(BuildConfig.FIREBASE_API_KEY)
                        .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                        .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                        .build();
                FirebaseApp.initializeApp(context, options);
            }
        } catch (Exception e) {
            Log.e(TAG, "Firebase initialization failed", e);
        }
    }

    public static String getToken(Context context) {
        if (FirebaseApp.getApps(context).isEmpty()) return null;
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        String[] token = {null};
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().getToken()
                    .addOnCompleteListener(task -> {
                        if (task.isSuccessful()) token[0] = task.getResult();
                        latch.countDown();
                    });
            latch.await(4, java.util.concurrent.TimeUnit.SECONDS);
            return token[0];
        } catch (Exception e) {
            Log.w(TAG, "FCM token unavailable", e);
            return null;
        }
    }

    public static void storeToken(Context context, String token) {
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString("fcm_token", token).apply();
    }

    public static String storedToken(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("fcm_token", null);
    }

    private static boolean isEmpty(String value) { return value == null || value.trim().isEmpty(); }
}
