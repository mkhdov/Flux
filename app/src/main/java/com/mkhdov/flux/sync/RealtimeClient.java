package com.mkhdov.flux.sync;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class RealtimeClient {
    private static final String TAG = "RealtimeClient";
    private final String websocketUrl;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable notify;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private WebSocket socket;
    private String token;
    private String lastToken;
    private int reconnectAttempts;
    private boolean stopped;
    private int ref = 1;

    public RealtimeClient(String baseUrl, String apiKey, Runnable notify) {
        String ws = baseUrl.replaceFirst("^http://", "ws://").replaceFirst("^https://", "wss://");
        this.websocketUrl = ws + "/realtime/v1/websocket?apikey=" + apiKey + "&vsn=1.0.0";
        this.notify = notify;
    }

    public void start(String accessToken) {
        this.token = accessToken;
        if (socket != null && connected.get() && accessToken.equals(lastToken)) return;
        if (socket != null && !connected.get()) return;
        if (socket != null) disconnect();
        stopped = false;
        open();
    }

    public void stop() {
        stopped = true;
        disconnect();
    }

    public boolean isConnected() { return connected.get(); }

    private void open() {
        if (stopped) return;
        Request request = new Request.Builder().url(websocketUrl).build();
        socket = client.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                connected.set(true);
                reconnectAttempts = 0;
                sendJoin(webSocket);
            }

            @Override public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                try {
                    JsonObject message = JsonParser.parseString(text).getAsJsonObject();
                    String event = message.has("event") ? message.get("event").getAsString() : "";
                    if (event.equals("phx_reply")) {
                        JsonObject payload = message.has("payload") ? message.getAsJsonObject("payload") : null;
                        if (payload != null && payload.has("status") && payload.get("status").getAsString().equals("ok")) {
                            connected.set(true);
                        }
                    } else if (event.equals("postgres_changes") || event.equals("INSERT") || event.equals("UPDATE") || event.equals("DELETE")) {
                        if (notify != null) notify.run();
                    } else if (event.equals("phx_error")) {
                        connected.set(false);
                    }
                } catch (Exception ignored) { }
            }

            @Override public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
                connected.set(false);
                socket = null;
                scheduleReconnect();
            }

            @Override public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                connected.set(false);
                socket = null;
                scheduleReconnect();
            }
        });
    }

    private void sendJoin(WebSocket ws) {
        try {
            JsonObject payload = new JsonObject();
            JsonObject config = new JsonObject();
            com.google.gson.JsonArray changes = new com.google.gson.JsonArray();
            JsonObject table = new JsonObject();
            table.addProperty("event", "*");
            table.addProperty("schema", "public");
            table.addProperty("table", "sync_changes");
            changes.add(table);
            config.add("postgres_changes", changes);
            payload.add("config", config);
            payload.addProperty("access_token", token);
            JsonObject join = new JsonObject();
            join.addProperty("topic", "realtime:public:sync_changes");
            join.addProperty("event", "phx_join");
            join.add("payload", payload);
            join.addProperty("ref", String.valueOf(++ref));
            ws.send(join.toString());
            lastToken = token;
        } catch (Exception e) {
            Log.w(TAG, "Unable to join realtime channel", e);
        }
    }

    private void disconnect() {
        connected.set(false);
        if (socket != null) {
            try { socket.close(1000, "closing"); } catch (Exception ignored) { }
            socket = null;
        }
    }

    private void scheduleReconnect() {
        if (stopped) return;
        reconnectAttempts++;
        long delay = Math.min(60000L, (long) Math.pow(2, Math.min(6, reconnectAttempts)) * 1000L);
        main.removeCallbacks(reconnectRunnable);
        main.postDelayed(reconnectRunnable, delay);
    }

    private final Runnable reconnectRunnable = () -> {
        if (!stopped && socket == null) open();
    };
}
