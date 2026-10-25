package com.mkhdov.flux.data.remote;

public class SupabaseException extends Exception {
    public final int status;
    public final String code;
    public final String body;

    public SupabaseException(int status, String code, String message, String body) {
        super(message == null ? "Supabase request failed (" + status + ")" : message);
        this.status = status;
        this.code = code;
        this.body = body;
    }
}
