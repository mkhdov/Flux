package com.mkhdov.flux.data.repo;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.JsonObject;
import com.mkhdov.flux.data.remote.SupabaseApi;
import com.mkhdov.flux.data.remote.SupabaseException;
import com.mkhdov.flux.util.SecurePrefs;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AuthManager {
    private static final String TAG = "AuthManager";
    private static final String K_ACCESS = "access_token";
    private static final String K_REFRESH = "refresh_token";
    private static final String K_EXPIRES = "expires_at";
    private static final String K_USER_ID = "user_id";
    private static final String K_EMAIL = "email";

    private static volatile AuthManager INSTANCE;
    private final SecurePrefs secure;
    private final SharedPreferences prefs;
    private final SupabaseApi api;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object refreshLock = new Object();

    public interface Callback {
        void onSuccess();
        void onError(String message);
    }

    public interface TokenCallback {
        void onToken(String token);
        void onError(String message, boolean requiresLogin);
    }

    public static AuthManager get(Context context) {
        if (INSTANCE == null) {
            synchronized (AuthManager.class) {
                if (INSTANCE == null) INSTANCE = new AuthManager(context.getApplicationContext());
            }
        }
        return INSTANCE;
    }

    private AuthManager(Context context) {
        secure = new SecurePrefs(context);
        prefs = context.getSharedPreferences("flux_prefs", Context.MODE_PRIVATE);
        api = new SupabaseApi();
    }

    public SupabaseApi api() { return api; }

    public boolean isConfigured() { return api.isConfigured(); }

    public boolean hasSession() {
        return secure.get(K_REFRESH) != null;
    }

    public boolean isLocalOnly() { return prefs.getBoolean("local_only", false); }

    public void setLocalOnly(boolean localOnly) { prefs.edit().putBoolean("local_only", localOnly).apply(); }

    public String userId() { return secure.get(K_USER_ID); }

    public String email() { return secure.get(K_EMAIL); }

    public boolean isSignedIn() { return hasSession(); }

    public boolean canUseApp() { return hasSession() || isLocalOnly(); }

    public void login(String email, String password, Callback callback) {
        executor.execute(() -> {
            try {
                JsonObject response = api.signIn(email.trim(), password);
                applySession(response, email.trim());
                main.post(callback::onSuccess);
            } catch (IOException e) {
                main.post(() -> callback.onError("Cannot reach Supabase. Check your connection."));
            } catch (SupabaseException e) {
                String message = e.status == 400 || e.status == 401 ? "Invalid email or password." : e.getMessage();
                main.post(() -> callback.onError(message));
            }
        });
    }

    public void recover(String email, Callback callback) {
        executor.execute(() -> {
            try {
                api.recover(email.trim());
                main.post(callback::onSuccess);
            } catch (Exception e) {
                main.post(() -> callback.onError(e.getMessage() == null ? "Could not send recovery email." : e.getMessage()));
            }
        });
    }

    private void applySession(JsonObject response, String fallbackEmail) {
        String access = str(response, "access_token");
        String refresh = str(response, "refresh_token");
        long expiresIn = response.has("expires_in") ? response.get("expires_in").getAsLong() : 3600;
        long expiresAt = response.has("expires_at") ? response.get("expires_at").getAsLong() * 1000L : System.currentTimeMillis() + expiresIn * 1000L;
        JsonObject user = response.has("user") && response.get("user").isJsonObject() ? response.getAsJsonObject("user") : null;
        String userId = user != null ? str(user, "id") : null;
        String email = user != null ? str(user, "email") : fallbackEmail;
        secure.put(K_ACCESS, access);
        if (refresh != null) secure.put(K_REFRESH, refresh);
        secure.put(K_EXPIRES, String.valueOf(expiresAt));
        if (userId != null) secure.put(K_USER_ID, userId);
        if (email != null) secure.put(K_EMAIL, email);
        prefs.edit().putBoolean("local_only", false).apply();
    }

    public String getAccessTokenBlocking() {
        String access = secure.get(K_ACCESS);
        long expires = parseLong(secure.get(K_EXPIRES), 0);
        if (access != null && expires > System.currentTimeMillis() + 60000L) return access;
        synchronized (refreshLock) {
            access = secure.get(K_ACCESS);
            expires = parseLong(secure.get(K_EXPIRES), 0);
            if (access != null && expires > System.currentTimeMillis() + 60000L) return access;
            String refresh = secure.get(K_REFRESH);
            if (refresh == null) return null;
            try {
                JsonObject response = api.refresh(refresh);
                applySession(response, secure.get(K_EMAIL));
                return secure.get(K_ACCESS);
            } catch (SupabaseException e) {
                if (e.status == 400 || e.status == 401 || e.status == 403) {
                    clearSession();
                }
                Log.w(TAG, "Token refresh failed: " + e.getMessage());
                return null;
            } catch (IOException e) {
                Log.w(TAG, "Token refresh network failure", e);
                return access;
            }
        }
    }

    public void getAccessToken(TokenCallback callback) {
        executor.execute(() -> {
            String token = getAccessTokenBlocking();
            if (token == null) {
                main.post(() -> callback.onError("Sign in to sync", !hasSession()));
            } else {
                main.post(() -> callback.onToken(token));
            }
        });
    }

    public void forceRefresh() {
        synchronized (refreshLock) {
            secure.remove(K_EXPIRES);
        }
    }

    public void logout(boolean blockUntilComplete) {
        Runnable task = () -> {
            String token = getAccessTokenBlocking();
            if (token != null) {
                try { api.signOut(token); } catch (Exception ignored) { }
            }
            clearSession();
        };
        if (blockUntilComplete) task.run(); else executor.execute(task);
    }

    public void clearSession() {
        secure.remove(K_ACCESS);
        secure.remove(K_REFRESH);
        secure.remove(K_EXPIRES);
        secure.remove(K_USER_ID);
        secure.remove(K_EMAIL);
    }

    public static String str(JsonObject o, String key) {
        try { return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null; }
        catch (Exception e) { return null; }
    }

    private static long parseLong(String value, long fallback) {
        try { return Long.parseLong(value); } catch (Exception e) { return fallback; }
    }
}
