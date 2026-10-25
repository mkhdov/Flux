package com.mkhdov.flux.sync;

public class SyncStatus {
    public enum State { LOCAL_ONLY, OFFLINE, SYNCING, SYNCED, NEEDS_ATTENTION, SIGNED_OUT }

    public final State state;
    public final String message;
    public final int pending;
    public final int conflicts;
    public final long lastSync;
    public final boolean realtime;

    public SyncStatus(State state, String message, int pending, int conflicts, long lastSync, boolean realtime) {
        this.state = state;
        this.message = message;
        this.pending = pending;
        this.conflicts = conflicts;
        this.lastSync = lastSync;
        this.realtime = realtime;
    }

    public String label() {
        switch (state) {
            case SYNCING: return "Syncing…";
            case SYNCED: return "Synced";
            case OFFLINE: return "Offline";
            case NEEDS_ATTENTION: return "Needs attention";
            case SIGNED_OUT: return "Sign in to sync";
            case LOCAL_ONLY:
            default: return "On this device";
        }
    }
}
