package com.mkhdov.flux.ui.common;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;
import com.mkhdov.flux.R;
import com.mkhdov.flux.util.EventPalette;

public final class UiUtils {
    private UiUtils() { }

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static void snack(View view, String message) {
        if (view == null || message == null) return;
        Snackbar.make(view, message, Snackbar.LENGTH_LONG).setAnchorView(findAnchor(view)).show();
    }

    private static View findAnchor(View view) {
        View root = view.getRootView();
        return root.findViewById(R.id.main_bottom_today) != null ? root.findViewById(R.id.main_bottom_today) : view;
    }

    public static void colorDot(View view, String hex) {
        if (view == null) return;
        int color = EventPalette.parse(hex, ContextCompat.getColor(view.getContext(), R.color.accent));
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        view.setBackground(drawable);
    }

    public static void hideKeyboard(View view) {
        if (view == null) return;
        InputMethodManager imm = (InputMethodManager) view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    public static void showKeyboard(View view) {
        if (view == null) return;
        view.requestFocus();
        InputMethodManager imm = (InputMethodManager) view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
    }
}
