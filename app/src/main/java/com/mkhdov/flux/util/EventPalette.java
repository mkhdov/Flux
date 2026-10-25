package com.mkhdov.flux.util;

import android.graphics.Color;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

public final class EventPalette {
    public static final Pattern HEX = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    public static final List<NamedColor> COLORS = Arrays.asList(
            new NamedColor("Tomato", "#D50000"),
            new NamedColor("Flamingo", "#E67C73"),
            new NamedColor("Tangerine", "#F4511E"),
            new NamedColor("Banana", "#F6BF26"),
            new NamedColor("Sage", "#33B679"),
            new NamedColor("Basil", "#0B8043"),
            new NamedColor("Peacock", "#039BE5"),
            new NamedColor("Blueberry", "#3F51B5"),
            new NamedColor("Lavender", "#7986CB"),
            new NamedColor("Grape", "#8E24AA"),
            new NamedColor("Graphite", "#616161"),
            new NamedColor("Cocoa", "#795548"),
            new NamedColor("Cobalt", "#4285F4"),
            new NamedColor("Radicchio", "#AD1457")
    );

    private EventPalette() { }

    public static boolean isValid(String hex) {
        return hex != null && HEX.matcher(hex).matches();
    }

    public static String normalize(String hex) {
        return isValid(hex) ? hex.toUpperCase() : COLORS.get(0).hex;
    }

    public static int parse(String hex, int fallback) {
        if (!isValid(hex)) return fallback;
        try { return Color.parseColor(hex); } catch (Exception e) { return fallback; }
    }

    public static int onColor(int color) {
        double luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0;
        return luminance > 0.62 ? Color.parseColor("#1A1A1A") : Color.WHITE;
    }

    public static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    public static String nameFor(String hex) {
        if (hex == null) return "";
        for (NamedColor c : COLORS) if (c.hex.equalsIgnoreCase(hex)) return c.name;
        return hex;
    }

    public static final class NamedColor {
        public final String name;
        public final String hex;
        public NamedColor(String name, String hex) { this.name = name; this.hex = hex; }
    }
}
