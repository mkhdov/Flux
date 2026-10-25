package com.mkhdov.flux.data.remote;

import android.util.Base64;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mkhdov.flux.BuildConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;

public class SupabaseApi {
    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final MediaType TUS = MediaType.get("application/offset+octet-stream");
    private static final int CHUNK_SIZE = 6 * 1024 * 1024;
    private final Gson gson = new Gson();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();
    private final OkHttpClient uploadClient = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.MINUTES)
            .build();

    public boolean isConfigured() {
        return BuildConfig.SUPABASE_URL != null && !BuildConfig.SUPABASE_URL.trim().isEmpty()
                && BuildConfig.SUPABASE_ANON_KEY != null && !BuildConfig.SUPABASE_ANON_KEY.trim().isEmpty();
    }

    public String baseUrl() {
        String value = BuildConfig.SUPABASE_URL == null ? "" : BuildConfig.SUPABASE_URL.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    public JsonObject signIn(String email, String password) throws IOException, SupabaseException {
        JsonObject body = new JsonObject();
        body.addProperty("email", email);
        body.addProperty("password", password);
        return authRequest("/auth/v1/token?grant_type=password", body);
    }

    public JsonObject refresh(String refreshToken) throws IOException, SupabaseException {
        JsonObject body = new JsonObject();
        body.addProperty("refresh_token", refreshToken);
        return authRequest("/auth/v1/token?grant_type=refresh_token", body);
    }

    public void recover(String email) throws IOException, SupabaseException {
        JsonObject body = new JsonObject();
        body.addProperty("email", email);
        Request req = baseRequest("/auth/v1/recover").post(RequestBody.create(body.toString(), JSON)).build();
        exec(req, false);
    }

    public void signOut(String accessToken) throws IOException {
        Request req = baseRequest("/auth/v1/logout")
                .header("Authorization", "Bearer " + accessToken)
                .post(RequestBody.create("{}", JSON)).build();
        try { exec(req, false); } catch (SupabaseException ignored) { }
    }

    public JsonElement rpc(String function, JsonObject params, String accessToken) throws IOException, SupabaseException {
        Request req = baseRequest("/rest/v1/rpc/" + function)
                .header("Authorization", "Bearer " + accessToken)
                .post(RequestBody.create(params.toString(), JSON)).build();
        return exec(req, true);
    }

    public void ackCursor(String deviceId, long cursor, String accessToken) throws IOException, SupabaseException {
        JsonObject p = new JsonObject();
        p.addProperty("p_device_id", deviceId);
        p.addProperty("p_cursor", cursor);
        rpc("ack_sync_cursor", p, accessToken);
    }

    public void registerDevice(JsonObject installation, String accessToken) throws IOException, SupabaseException {
        HttpUrl url = HttpUrl.parse(baseUrl() + "/rest/v1/device_installations").newBuilder()
                .addQueryParameter("on_conflict", "owner_id,device_id").build();
        Request req = new Request.Builder().url(url)
                .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .header("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(RequestBody.create(installation.toString(), JSON)).build();
        exec(req, false);
    }

    public String signedUrl(String bucket, String path, int expiresSeconds, String accessToken) throws IOException, SupabaseException {
        HttpUrl url = HttpUrl.parse(baseUrl()).newBuilder()
                .addPathSegments("storage/v1/object/sign")
                .addPathSegment(bucket)
                .addPathSegments(path)
                .build();
        JsonObject body = new JsonObject();
        body.addProperty("expiresIn", expiresSeconds);
        Request req = new Request.Builder().url(url)
                .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .header("Authorization", "Bearer " + accessToken)
                .post(RequestBody.create(body.toString(), JSON)).build();
        JsonElement element = exec(req, true);
        if (element == null || !element.isJsonObject()) return null;
        JsonObject obj = element.getAsJsonObject();
        String signed = obj.has("signedURL") ? obj.get("signedURL").getAsString() : obj.has("signedUrl") ? obj.get("signedUrl").getAsString() : null;
        if (signed == null) return null;
        if (signed.startsWith("http")) return signed;
        return baseUrl() + "/storage/v1" + (signed.startsWith("/") ? signed : "/" + signed);
    }

    public void deleteObject(String bucket, String path, String accessToken) throws IOException, SupabaseException {
        HttpUrl url = HttpUrl.parse(baseUrl()).newBuilder()
                .addPathSegments("storage/v1/object")
                .addPathSegment(bucket)
                .addPathSegments(path)
                .build();
        Request req = new Request.Builder().url(url).delete()
                .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .header("Authorization", "Bearer " + accessToken).build();
        exec(req, false);
    }

    public byte[] download(String signedUrl) throws IOException {
        Request req = new Request.Builder().url(signedUrl).get().build();
        try (Response response = client.newCall(req).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) throw new IOException("Download failed: " + response.code());
            return body.bytes();
        }
    }

    public String uploadResumable(File file, String bucket, String objectPath, String mimeType,
                                  String accessToken, String existingSession, Progress progress,
                                  AtomicBoolean cancelled) throws IOException, SupabaseException {
        long total = file.length();
        String sessionUrl = existingSession;
        long offset = 0;
        if (sessionUrl != null && !sessionUrl.isEmpty()) {
            offset = queryOffset(sessionUrl, accessToken);
            if (offset < 0) sessionUrl = null;
        }
        if (sessionUrl == null || sessionUrl.isEmpty()) {
            HttpUrl createUrl = HttpUrl.parse(baseUrl()).newBuilder().addPathSegments("storage/v1/upload/resumable").build();
            String metadata = "bucketName " + b64(bucket) + ",objectName " + b64(objectPath)
                    + ",contentType " + b64(mimeType == null ? "application/octet-stream" : mimeType)
                    + ",cacheControl " + b64("3600");
            Request create = new Request.Builder().url(createUrl)
                    .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Tus-Resumable", "1.0.0")
                    .header("Upload-Length", String.valueOf(total))
                    .header("Upload-Metadata", metadata)
                    .header("x-upsert", "true")
                    .post(RequestBody.create(new byte[0]))
                    .build();
            try (Response response = uploadClient.newCall(create).execute()) {
                if (!response.isSuccessful()) throw error(response);
                String location = response.header("Location");
                if (location == null) throw new IOException("Upload session missing Location header");
                sessionUrl = resolve(baseUrl(), location);
            }
            offset = 0;
        }
        if (progress != null) progress.onProgress((int) (offset * 100L / Math.max(1L, total)), sessionUrl);
        while (offset < total) {
            if (cancelled != null && cancelled.get()) throw new UploadCancelledException("Upload cancelled", sessionUrl);
            int length = (int) Math.min(CHUNK_SIZE, total - offset);
            RequestBody chunk = new FileChunkBody(file, offset, length, total, progress, sessionUrl, cancelled);
            Request patch = new Request.Builder().url(sessionUrl)
                    .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Tus-Resumable", "1.0.0")
                    .header("Upload-Offset", String.valueOf(offset))
                    .header("Content-Type", "application/offset+octet-stream")
                    .method("PATCH", chunk)
                    .build();
            try (Response response = uploadClient.newCall(patch).execute()) {
                if (response.code() == 409 || response.code() == 404) {
                    long serverOffset = queryOffset(sessionUrl, accessToken);
                    if (serverOffset < 0) throw new IOException("Upload session expired");
                    offset = serverOffset;
                    continue;
                }
                if (!response.isSuccessful()) throw error(response);
                String newOffset = response.header("Upload-Offset");
                offset = newOffset == null ? offset + length : Long.parseLong(newOffset);
                if (progress != null) progress.onProgress((int) (offset * 100L / Math.max(1L, total)), sessionUrl);
            }
        }
        return sessionUrl;
    }

    public long queryOffset(String sessionUrl, String accessToken) throws IOException {
        Request head = new Request.Builder().url(sessionUrl).head()
                .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .header("Authorization", "Bearer " + accessToken)
                .header("Tus-Resumable", "1.0.0").build();
        try (Response response = uploadClient.newCall(head).execute()) {
            if (response.code() == 404 || response.code() == 410 || response.code() == 423) return -1;
            if (!response.isSuccessful()) return -1;
            String offset = response.header("Upload-Offset");
            return offset == null ? -1 : Long.parseLong(offset);
        }
    }

    private long queryTotal(String sessionUrl, String accessToken) {
        return -1;
    }

    private JsonObject authRequest(String path, JsonObject body) throws IOException, SupabaseException {
        Request req = baseRequest(path).post(RequestBody.create(body.toString(), JSON)).build();
        JsonElement element = exec(req, true);
        if (element == null || !element.isJsonObject()) throw new SupabaseException(0, "invalid_response", "Invalid authentication response", "");
        return element.getAsJsonObject();
    }

    private Request.Builder baseRequest(String path) {
        return new Request.Builder().url(baseUrl() + path)
                .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
    }

    private JsonElement exec(Request request, boolean parseJson) throws IOException, SupabaseException {
        try (Response response = client.newCall(request).execute()) {
            String text = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                JsonObject obj = parseObject(text);
                String message = null;
                if (obj != null) {
                    if (obj.has("message")) message = obj.get("message").getAsString();
                    else if (obj.has("error_description")) message = obj.get("error_description").getAsString();
                    else if (obj.has("msg")) message = obj.get("msg").getAsString();
                    else if (obj.has("error")) message = obj.get("error").isJsonPrimitive() ? obj.get("error").getAsString() : obj.get("error").toString();
                }
                if (response.code() == 401) message = message == null ? "Session expired" : message;
                throw new SupabaseException(response.code(), obj == null || !obj.has("code") ? null : obj.get("code").getAsString(), message, text);
            }
            if (!parseJson || text.isEmpty()) return null;
            try { return JsonParser.parseString(text); } catch (Exception e) { throw new IOException("Invalid JSON response", e); }
        }
    }

    private JsonObject parseObject(String text) {
        try { return JsonParser.parseString(text).getAsJsonObject(); } catch (Exception e) { return null; }
    }

    private SupabaseException error(Response response) throws IOException {
        String text = response.body() == null ? "" : response.body().string();
        JsonObject obj = parseObject(text);
        String message = obj != null && obj.has("message") ? obj.get("message").getAsString() : "Request failed (" + response.code() + ")";
        return new SupabaseException(response.code(), obj != null && obj.has("error") ? obj.get("error").getAsString() : null, message, text);
    }

    private String b64(String value) {
        return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    private String resolve(String base, String location) {
        if (location.startsWith("http://") || location.startsWith("https://")) return location;
        HttpUrl b = HttpUrl.parse(base);
        if (b == null) return base + location;
        HttpUrl resolved = b.resolve(location);
        return resolved == null ? base + location : resolved.toString();
    }

    public interface Progress { void onProgress(int percent, String sessionUrl); }

    public static class UploadCancelledException extends IOException {
        public final String sessionUrl;
        public UploadCancelledException(String message, String sessionUrl) { super(message); this.sessionUrl = sessionUrl; }
    }

    public static class UploadInterruptedException extends IOException {
        public final String sessionUrl;
        public UploadInterruptedException(String message, String sessionUrl, Throwable cause) {
            super(message, cause); this.sessionUrl = sessionUrl;
        }
    }

    private class FileChunkBody extends RequestBody {
        private final File file;
        private final long offset;
        private final int length;
        private final long total;
        private final Progress progress;
        private final String sessionUrl;
        private final AtomicBoolean cancelled;

        FileChunkBody(File file, long offset, int length, long total, Progress progress, String sessionUrl, AtomicBoolean cancelled) {
            this.file = file; this.offset = offset; this.length = length; this.total = total;
            this.progress = progress; this.sessionUrl = sessionUrl; this.cancelled = cancelled;
        }

        @Override public MediaType contentType() { return TUS; }
        @Override public long contentLength() { return length; }

        @Override public void writeTo(BufferedSink sink) throws IOException {
            byte[] buffer = new byte[64 * 1024];
            long written = 0;
            try (FileInputStream in = new FileInputStream(file)) {
                long skipped = 0;
                while (skipped < offset) {
                    long s = in.skip(offset - skipped);
                    if (s <= 0) break;
                    skipped += s;
                }
                while (written < length) {
                    if (cancelled != null && cancelled.get()) throw new UploadCancelledException("Upload cancelled", sessionUrl);
                    int read = in.read(buffer, 0, (int) Math.min(buffer.length, length - written));
                    if (read < 0) break;
                    sink.write(buffer, 0, read);
                    written += read;
                    if (progress != null) {
                        int percent = (int) ((offset + written) * 100L / Math.max(1L, total));
                        progress.onProgress(percent, sessionUrl);
                    }
                }
            }
        }
    }
}
