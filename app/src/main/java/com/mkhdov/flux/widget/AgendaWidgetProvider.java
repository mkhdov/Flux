package com.mkhdov.flux.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;

import com.mkhdov.flux.R;
import com.mkhdov.flux.ui.event.EventEditorActivity;

import java.time.LocalDate;
import java.util.Set;

public class AgendaWidgetProvider extends AppWidgetProvider {
    public static final String ACTION_REFRESH = "com.mkhdov.flux.widget.REFRESH";

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int id : appWidgetIds) updateWidget(context, id);
    }

    @Override public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_REFRESH.equals(intent.getAction())) {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            int[] ids = manager.getAppWidgetIds(new ComponentName(context, AgendaWidgetProvider.class));
            for (int id : ids) updateWidget(context, id);
        }
    }

    @Override public void onDeleted(Context context, int[] appWidgetIds) {
        for (int id : appWidgetIds) WidgetPrefs.get(context, id).delete(context);
    }

    @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager, int appWidgetId, Bundle newOptions) {
        updateWidget(context, appWidgetId);
    }

    public static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, AgendaWidgetProvider.class));
        for (int id : ids) updateWidget(context, id);
    }

    public static void updateWidget(Context context, int widgetId) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        WidgetPrefs prefs = WidgetPrefs.get(context, widgetId);
        String style = prefs.style();
        int primary;
        int secondary;
        int divider;
        int titleColor;
        int background;
        int accent = Color.parseColor("#FF3B30");
        if (WidgetPrefs.STYLE_WHITE.equals(style)) {
            primary = Color.parseColor("#FF111114");
            secondary = Color.parseColor("#FF8A8A8E");
            divider = Color.parseColor("#FFE5E5EA");
            titleColor = primary;
            background = R.drawable.bg_widget_white;
        } else if (WidgetPrefs.STYLE_DARK.equals(style)) {
            primary = Color.parseColor("#FFF5F5F7");
            secondary = Color.parseColor("#FF98989D");
            divider = Color.parseColor("#FF2C2C2E");
            titleColor = primary;
            background = R.drawable.bg_widget_dark;
        } else {
            boolean night = (context.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            primary = night ? Color.parseColor("#FFF5F5F7") : Color.parseColor("#FF111114");
            secondary = night ? Color.parseColor("#FF98989D") : Color.parseColor("#FF8A8A8E");
            divider = night ? Color.parseColor("#552C2C2E") : Color.parseColor("#33C7C7CC");
            titleColor = primary;
            background = R.drawable.bg_widget_transparent;
        }

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_agenda);
        views.setInt(R.id.widget_root, "setBackgroundResource", background);
        views.setTextColor(R.id.widget_title, titleColor);
        views.setInt(R.id.widget_add, "setColorFilter", accent);
        views.setInt(R.id.widget_refresh, "setColorFilter", secondary);
        views.setViewVisibility(R.id.widget_add, prefs.showPlus() ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.widget_refresh, prefs.showPlus() ? View.VISIBLE : View.GONE);

        Intent addIntent = new Intent(context, WidgetClickRouterActivity.class)
                .putExtra("new_event", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .setData(android.net.Uri.parse("flux://widget/add/" + widgetId));
        PendingIntent addPending = PendingIntent.getActivity(context, 2000 + widgetId, addIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_add, addPending);

        Intent refreshIntent = new Intent(context, WidgetClickRouterActivity.class)
                .putExtra("refresh", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .setData(android.net.Uri.parse("flux://widget/refresh/" + widgetId));
        PendingIntent refreshPending = PendingIntent.getActivity(context, 3000 + widgetId, refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_refresh, refreshPending);

        Intent serviceIntent = new Intent(context, AgendaWidgetService.class)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        views.setRemoteAdapter(R.id.widget_list, serviceIntent);

        Intent template = new Intent(context, WidgetClickRouterActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .setData(android.net.Uri.parse("flux://widget/click/" + widgetId));
        PendingIntent clickPending = PendingIntent.getActivity(context, 1000 + widgetId, template,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        views.setPendingIntentTemplate(R.id.widget_list, clickPending);

        manager.updateAppWidget(widgetId, views);
        manager.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_list);
    }
}
