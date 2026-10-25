package com.mkhdov.flux.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class HashUtils {
    private HashUtils() { }

    public static int stableId(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return ((hash[0] & 0xFF) << 24) | ((hash[1] & 0xFF) << 16) | ((hash[2] & 0xFF) << 8) | (hash[3] & 0xFF);
        } catch (Exception e) {
            return value.hashCode();
        }
    }

    public static int positiveId(String value) {
        return stableId(value) & 0x7FFFFFFF;
    }
}
