package com.mkhdov.flux.widget;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class WidgetPrefs {
    public static final String STYLE_TRANSPARENT = "transparent";
    public static final String STYLE_WHITE = "white";
    public static final String STYLE_DARK = "dark";

    private final SharedPreferences prefs;
    private final int widgetId;

    private WidgetPrefs(Context context, int widgetId) {
        this.widgetId = widgetId;
        this.prefs = context.getSharedPreferences("flux_widget_" + widgetId, Context.MODE_PRIVATE);
    }

    public static WidgetPrefs get(Context context, int widgetId) { return new WidgetPrefs(context, widgetId); }

    public String style() { return prefs.getString("style", STYLE_TRANSPARENT); }
    public int days() { return prefs.getInt("days", 7); }
    public int maxEvents() { return prefs.getInt("max_events", 20); }
    public boolean showDetails() { return prefs.getBoolean("show_details", true); }
    public boolean dimPast() { return prefs.getBoolean("dim_past", true); }
    public boolean showPlus() { return prefs.getBoolean("show_plus", true); }
    public String dateAction() { return prefs.getString("date_action", "day"); }
    public Set<String> calendars() {
        Set<String> result = new HashSet<>();
        String csv = prefs.getString("calendars", "*");
        if (csv == null || csv.isEmpty() || csv.equals("*")) return result;
        if (csv.equals("#none")) { result.add("__none__"); return result; }
        for (String id : csv.split(",")) if (!id.isEmpty()) result.add(id);
        return result;
    }

    public String rawCalendars() { return prefs.getString("calendars", "*"); }

    public void saveRawCalendars(String raw) { prefs.edit().putString("calendars", raw).apply(); }

    public void save(String style, int days, int maxEvents, boolean showDetails, boolean dimPast,
                     boolean showPlus, String dateAction, Set<String> calendarIds) {
        prefs.edit()
                .putString("style", style)
                .putInt("days", days)
                .putInt("max_events", maxEvents)
                .putBoolean("show_details", showDetails)
                .putBoolean("dim_past", dimPast)
                .putBoolean("show_plus", showPlus)
                .putString("date_action", dateAction)
                .putString("calendars", calendarIds == null || calendarIds.isEmpty() ? "#none" : String.join(",", calendarIds))
                .apply();
    }

    public void delete(Context context) {
        prefs.edit().clear().apply();
        context.deleteSharedPreferences("flux_widget_" + widgetId);
    }
}
