package com.mkhdov.flux.ui.calendar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.mkhdov.flux.R;
import com.mkhdov.flux.util.TimeUtils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Locale;

public class WeekStripView extends View {
    public interface OnDateSelectedListener { void onDateSelected(LocalDate date); }

    private final Paint letterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint numberPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint todayRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF circle = new RectF();
    private LocalDate selectedDate = LocalDate.now();
    private LocalDate pressedDate;
    private OnDateSelectedListener listener;
    private int accent;
    private int textSecondary;
    private int textPrimary;
    private float downX;

    public WeekStripView(Context context) { this(context, null); }

    public WeekStripView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        setClickable(true);
        float density = getResources().getDisplayMetrics().scaledDensity;
        letterPaint.setTextSize(10.5f * density);
        letterPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        letterPaint.setTextAlign(Paint.Align.CENTER);
        numberPaint.setTextSize(15f * density);
        numberPaint.setTextAlign(Paint.Align.CENTER);
        accent = ContextCompat.getColor(context, R.color.accent);
        textSecondary = ContextCompat.getColor(context, R.color.text_secondary);
        textPrimary = ContextCompat.getColor(context, R.color.text_primary);
        circlePaint.setStyle(Paint.Style.FILL);
        todayRingPaint.setStyle(Paint.Style.STROKE);
        todayRingPaint.setStrokeWidth(1.3f * getResources().getDisplayMetrics().density);
    }

    public void setOnDateSelectedListener(OnDateSelectedListener listener) { this.listener = listener; }

    public void setSelectedDate(LocalDate date) {
        if (date == null) return;
        boolean changed = !date.equals(selectedDate);
        selectedDate = date;
        if (changed) invalidate();
    }

    public LocalDate getSelectedDate() { return selectedDate; }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        LocalDate weekStart = TimeUtils.weekStart(selectedDate);
        LocalDate today = LocalDate.now();
        float width = getWidth();
        float height = getHeight();
        float cell = width / 7f;
        float letterY = height * 0.28f;
        letterPaint.setColor(textSecondary);
        for (int i = 0; i < 7; i++) {
            DayOfWeek day = weekStart.plusDays(i).getDayOfWeek();
            String letter = day.getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault()).toUpperCase(Locale.getDefault());
            canvas.drawText(letter, cell * i + cell / 2f, letterY, letterPaint);
        }
        float radius = Math.min(cell * 0.34f, height * 0.24f);
        float centerY = height * 0.68f;
        for (int i = 0; i < 7; i++) {
            LocalDate date = weekStart.plusDays(i);
            float cx = cell * i + cell / 2f;
            boolean selected = date.equals(selectedDate);
            boolean isToday = date.equals(today);
            boolean pressed = date.equals(pressedDate);
            if (selected || pressed) {
                circlePaint.setColor(pressed && !selected ? 0x22D50000 : accent);
                circle.set(cx - radius, centerY - radius, cx + radius, centerY + radius);
                canvas.drawRoundRect(circle, radius, radius, circlePaint);
            } else if (isToday) {
                todayRingPaint.setColor(accent);
                canvas.drawCircle(cx, centerY, radius, todayRingPaint);
            }
            if (selected || pressed) {
                numberPaint.setColor(0xFFFFFFFF);
                numberPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            } else if (isToday) {
                numberPaint.setColor(accent);
                numberPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            } else {
                numberPaint.setColor(textPrimary);
                numberPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            }
            Paint.FontMetrics fm = numberPaint.getFontMetrics();
            float baseline = centerY - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(String.valueOf(date.getDayOfMonth()), cx, baseline, numberPaint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                pressedDate = dateAt(event.getX());
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                LocalDate over = dateAt(event.getX());
                if (over == null || !over.equals(pressedDate)) {
                    pressedDate = over;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                LocalDate up = dateAt(event.getX());
                LocalDate wasPressed = pressedDate;
                pressedDate = null;
                invalidate();
                if (wasPressed != null && wasPressed.equals(up) && Math.abs(event.getX() - downX) < dp(10)) {
                    selectedDate = up;
                    if (listener != null) listener.onDateSelected(up);
                    performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressedDate = null;
                invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    private LocalDate dateAt(float x) {
        float cell = getWidth() / 7f;
        if (cell <= 0) return null;
        int index = (int) (x / cell);
        if (index < 0 || index > 6) return null;
        return TimeUtils.weekStart(selectedDate).plusDays(index);
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
}
