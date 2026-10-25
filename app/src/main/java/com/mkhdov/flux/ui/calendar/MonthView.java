package com.mkhdov.flux.ui.calendar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.mkhdov.flux.R;
import com.mkhdov.flux.data.local.entity.CalendarEntity;
import com.mkhdov.flux.data.model.DaySnapshot;
import com.mkhdov.flux.data.model.EventInstance;
import com.mkhdov.flux.util.EventPalette;
import com.mkhdov.flux.util.TimeUtils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MonthView extends View {
    public interface Listener {
        void onDateSelected(LocalDate date);
        void onEventClick(EventInstance event);
        void onEventMoved(EventInstance event, LocalDate targetDate);
    }

    private static final int ROWS = 6;
    private static final int COLS = 7;

    private final Paint headerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint numberPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint todayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barAccentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dragPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint barTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint overflowPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    private final List<BarHit> bars = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int touchSlop;

    private YearMonth month = YearMonth.now();
    private LocalDate selectedDate = LocalDate.now();
    private DaySnapshot snapshot = new DaySnapshot();
    private Listener listener;
    private float headerHeight;
    private float cellWidth;
    private float cellHeight;
    private int accent;
    private int textPrimary;
    private int textSecondary;
    private int textTertiary;
    private int lineColor;
    private int textOnAccent;

    private float downX, downY, lastX, lastY;
    private BarHit pressedBar;
    private EventInstance dragging;
    private LocalDate dragTarget;
    private boolean dragActive;
    private final Runnable longPress = new Runnable() {
        @Override public void run() {
            if (pressedBar == null) return;
            dragging = pressedBar.instance;
            dragTarget = pressedBar.date;
            dragActive = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            invalidate();
        }
    };

    public MonthView(Context context) { this(context, null); }

    public MonthView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        setClickable(true);
        float density = getResources().getDisplayMetrics().density;
        float scaled = getResources().getDisplayMetrics().scaledDensity;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        headerPaint.setTextSize(10f * scaled);
        headerPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        headerPaint.setTextAlign(Paint.Align.CENTER);
        numberPaint.setTextSize(12f * scaled);
        numberPaint.setTextAlign(Paint.Align.CENTER);
        barTextPaint.setTextSize(8.3f * scaled);
        barTextPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        overflowPaint.setTextSize(8.3f * scaled);
        overflowPaint.setTextAlign(Paint.Align.RIGHT);
        gridPaint.setStrokeWidth(Math.max(1f, density * 0.6f));
        selectedPaint.setStyle(Paint.Style.FILL);
        todayPaint.setStyle(Paint.Style.STROKE);
        todayPaint.setStrokeWidth(density * 1.1f);
        barPaint.setStyle(Paint.Style.FILL);
        barAccentPaint.setStyle(Paint.Style.FILL);
        dragPaint.setStyle(Paint.Style.STROKE);
        dragPaint.setStrokeWidth(density * 1.5f);
        accent = ContextCompat.getColor(context, R.color.accent);
        textPrimary = ContextCompat.getColor(context, R.color.text_primary);
        textSecondary = ContextCompat.getColor(context, R.color.text_secondary);
        textTertiary = ContextCompat.getColor(context, R.color.text_tertiary);
        lineColor = ContextCompat.getColor(context, R.color.divider);
        textOnAccent = 0xFFFFFFFF;
    }

    public void setListener(Listener listener) { this.listener = listener; }

    public void setMonth(YearMonth month) {
        if (month == null || month.equals(this.month)) return;
        this.month = month;
        invalidate();
    }

    public void setSelectedDate(LocalDate date) {
        selectedDate = date == null ? LocalDate.now() : date;
        invalidate();
    }

    public void setSnapshot(DaySnapshot snapshot) {
        this.snapshot = snapshot == null ? new DaySnapshot() : snapshot;
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cellWidth = w / (float) COLS;
        headerHeight = Math.min(dp(28), h * 0.08f);
        cellHeight = (h - headerHeight) / (float) ROWS;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        bars.clear();
        float width = getWidth();
        float height = getHeight();
        if (width <= 0 || height <= 0) return;
        if (cellWidth == 0) {
            cellWidth = width / (float) COLS;
            headerHeight = Math.min(dp(28), height * 0.08f);
            cellHeight = (height - headerHeight) / (float) ROWS;
        }
        LocalDate gridStart = gridStart(month);
        LocalDate today = LocalDate.now();
        float headerBaseline = headerHeight * 0.68f;
        headerPaint.setColor(textSecondary);
        for (int c = 0; c < COLS; c++) {
            DayOfWeek day = gridStart.plusDays(c).getDayOfWeek();
            String label = day.getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault()).toUpperCase(Locale.getDefault());
            canvas.drawText(label, cellWidth * c + cellWidth / 2f, headerBaseline, headerPaint);
        }
        gridPaint.setColor(lineColor);
        canvas.drawLine(0, headerHeight, width, headerHeight, gridPaint);
        for (int r = 1; r < ROWS; r++) {
            float y = headerHeight + cellHeight * r;
            canvas.drawLine(0, y, width, y, gridPaint);
        }
        for (int i = 0; i < ROWS * COLS; i++) {
            int row = i / COLS;
            int col = i % COLS;
            LocalDate date = gridStart.plusDays(i);
            float left = cellWidth * col;
            float top = headerHeight + cellHeight * row;
            boolean inMonth = YearMonth.from(date).equals(month);
            boolean selected = date.equals(selectedDate);
            boolean isToday = date.equals(today);
            if (selected) {
                selectedPaint.setColor(EventPalette.withAlpha(accent, 26));
                tmp.set(left + dp(1.5f), top + dp(1.5f), left + cellWidth - dp(1.5f), top + cellHeight - dp(1.5f));
                canvas.drawRoundRect(tmp, dp(8), dp(8), selectedPaint);
            }
            float numberCx = left + cellWidth / 2f;
            float numberCy = top + Math.min(dp(11), cellHeight * 0.17f);
            if (selected || isToday) {
                float radius = Math.min(dp(10), cellHeight * 0.14f);
                if (selected) {
                    selectedPaint.setColor(accent);
                    canvas.drawCircle(numberCx, numberCy, radius, selectedPaint);
                } else {
                    todayPaint.setColor(accent);
                    canvas.drawCircle(numberCx, numberCy, radius, todayPaint);
                }
            }
            if (selected) {
                numberPaint.setColor(textOnAccent);
                numberPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            } else if (isToday) {
                numberPaint.setColor(accent);
                numberPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            } else {
                numberPaint.setColor(inMonth ? textPrimary : textTertiary);
                numberPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            }
            Paint.FontMetrics fm = numberPaint.getFontMetrics();
            float baseline = numberCy - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(String.valueOf(date.getDayOfMonth()), numberCx, baseline, numberPaint);
            if (!inMonth) {
                canvas.save();
                canvas.clipRect(left, top, left + cellWidth, top + cellHeight);
            }
            drawDayEvents(canvas, date, left, top, inMonth);
            if (!inMonth) canvas.restore();
        }
        if (dragActive && dragTarget != null) {
            LocalDate start = gridStart(month);
            int index = (int) java.time.temporal.ChronoUnit.DAYS.between(start, dragTarget);
            if (index >= 0 && index < ROWS * COLS) {
                int row = index / COLS;
                int col = index % COLS;
                dragPaint.setColor(accent);
                tmp.set(cellWidth * col + dp(2), headerHeight + cellHeight * row + dp(2),
                        cellWidth * (col + 1) - dp(2), headerHeight + cellHeight * (row + 1) - dp(2));
                canvas.drawRoundRect(tmp, dp(8), dp(8), dragPaint);
            }
        }
    }

    private void drawDayEvents(Canvas canvas, LocalDate date, float left, float top, boolean inMonth) {
        List<EventInstance> events = snapshot.eventsFor(date);
        if (events.isEmpty()) return;
        float barLeft = left + dp(3.5f);
        float barRight = left + cellWidth - dp(3.5f);
        if (barRight <= barLeft) return;
        float y = top + Math.min(dp(23), cellHeight * 0.38f);
        float barHeight = Math.min(dp(11.5f), cellHeight * 0.16f);
        float gap = dp(1.5f);
        int maxBars = 2;
        int available = (int) ((top + cellHeight - dp(2) - y) / (barHeight + gap));
        maxBars = Math.max(1, Math.min(maxBars, available));
        int shown = 0;
        int alpha = inMonth ? 255 : 110;
        for (EventInstance event : events) {
            if (shown >= maxBars) break;
            CalendarEntity calendar = snapshot.calendarFor(event.source.calendarId);
            String hex = event.source.color != null && EventPalette.isValid(event.source.color)
                    ? event.source.color : (calendar != null ? calendar.color : EventPalette.COLORS.get(0).hex);
            int color = EventPalette.parse(hex, accent);
            int barTop = (int) (y + shown * (barHeight + gap));
            tmp.set(barLeft, barTop, barRight, barTop + barHeight);
            if (event.source.allDay) {
                barPaint.setColor(EventPalette.withAlpha(color, alpha));
                canvas.drawRoundRect(tmp, dp(2.5f), dp(2.5f), barPaint);
                barTextPaint.setColor(EventPalette.onColor(color));
            } else {
                barPaint.setColor(EventPalette.withAlpha(color, inMonth ? 32 : 16));
                canvas.drawRoundRect(tmp, dp(2.5f), dp(2.5f), barPaint);
                barAccentPaint.setColor(EventPalette.withAlpha(color, alpha));
                tmp.set(barLeft, barTop, barLeft + dp(2), barTop + barHeight);
                canvas.drawRoundRect(tmp, dp(1), dp(1), barAccentPaint);
                barTextPaint.setColor(inMonth ? textPrimary : textTertiary);
            }
            float textLeft = barLeft + dp(3.5f);
            float textWidth = Math.max(0, barRight - textLeft - dp(2));
            CharSequence title = TextUtils.ellipsize(event.source.title == null ? "" : event.source.title,
                    barTextPaint, textWidth, TextUtils.TruncateAt.END);
            Paint.FontMetrics fm = barTextPaint.getFontMetrics();
            float textBaseline = barTop + barHeight / 2f - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(title.toString(), textLeft, textBaseline, barTextPaint);
            bars.add(new BarHit(event, date, new RectF(barLeft, barTop, barRight, barTop + barHeight)));
            shown++;
        }
        if (events.size() > shown) {
            int remaining = events.size() - shown;
            overflowPaint.setColor(textSecondary);
            float baseline = y + shown * (barHeight + gap) + barHeight * 0.8f;
            canvas.drawText("+" + remaining, barRight, baseline, overflowPaint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = event.getX();
                downY = lastY = event.getY();
                pressedBar = findBar(event.getX(), event.getY());
                dragActive = false;
                dragging = null;
                dragTarget = null;
                if (pressedBar != null) {
                    handler.postDelayed(longPress, 260);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                lastX = event.getX();
                lastY = event.getY();
                if (dragActive) {
                    dragTarget = dateAt(event.getX(), event.getY());
                    invalidate();
                    return true;
                }
                if (Math.abs(event.getX() - downX) > touchSlop || Math.abs(event.getY() - downY) > touchSlop) {
                    handler.removeCallbacks(longPress);
                }
                return true;
            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(longPress);
                if (dragActive) {
                    LocalDate target = dateAt(event.getX(), event.getY());
                    EventInstance moved = dragging;
                    dragActive = false;
                    dragging = null;
                    dragTarget = null;
                    invalidate();
                    if (target != null && moved != null && listener != null) listener.onEventMoved(moved, target);
                    return true;
                }
                if (Math.abs(event.getX() - downX) < touchSlop && Math.abs(event.getY() - downY) < touchSlop) {
                    BarHit hit = pressedBar != null ? pressedBar : findBar(event.getX(), event.getY());
                    if (hit != null) {
                        if (listener != null) listener.onEventClick(hit.instance);
                    } else {
                        LocalDate date = dateAt(event.getX(), event.getY());
                        if (date != null && listener != null) listener.onDateSelected(date);
                    }
                    performClick();
                }
                pressedBar = null;
                return true;
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPress);
                dragActive = false;
                dragging = null;
                dragTarget = null;
                pressedBar = null;
                invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    private BarHit findBar(float x, float y) {
        for (int i = bars.size() - 1; i >= 0; i--) {
            BarHit hit = bars.get(i);
            if (hit.rect.contains(x, y)) return hit;
        }
        return null;
    }

    private LocalDate dateAt(float x, float y) {
        float width = getWidth();
        float height = getHeight();
        if (x < 0 || x >= width || y < headerHeight || y >= height) return null;
        int col = Math.max(0, Math.min(COLS - 1, (int) (x / cellWidth)));
        int row = Math.max(0, Math.min(ROWS - 1, (int) ((y - headerHeight) / cellHeight)));
        return gridStart(month).plusDays(row * COLS + col);
    }

    private LocalDate gridStart(YearMonth ym) {
        return TimeUtils.weekStart(ym.atDay(1));
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }

    private static final class BarHit {
        final EventInstance instance;
        final LocalDate date;
        final RectF rect;
        BarHit(EventInstance instance, LocalDate date, RectF rect) {
            this.instance = instance;
            this.date = date;
            this.rect = rect;
        }
    }
}
