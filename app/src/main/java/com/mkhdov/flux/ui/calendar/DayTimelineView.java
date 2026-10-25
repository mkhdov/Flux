package com.mkhdov.flux.ui.calendar;

import android.content.Context;
import android.content.res.Configuration;
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
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.OverScroller;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.mkhdov.flux.R;
import com.mkhdov.flux.data.local.entity.CalendarEntity;
import com.mkhdov.flux.data.model.DaySnapshot;
import com.mkhdov.flux.data.model.EventInstance;
import com.mkhdov.flux.util.EventPalette;
import com.mkhdov.flux.util.TimeUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class DayTimelineView extends View {
    public interface Listener {
        void onEventClick(EventInstance event);
        void onEventMoved(EventInstance event, long newStart, long newEnd, boolean resized);
        void onEmptyLongPress(long startEpochMillis);
    }

    public static class ScrollState {
        public int scrollY;
        public float hourHeight;
    }

    private static final int SNAP_MINUTES = 15;

    private final Paint hourTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halfLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint eventFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint eventBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint eventBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint eventTitlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint eventMetaPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nowDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dragLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dragLabelBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final OverScroller scroller;
    private final ScaleGestureDetector scaleDetector;
    private VelocityTracker velocityTracker;
    private final int touchSlop;
    private final int minHourHeight;
    private final int maxHourHeight;
    private final float density;
    private final boolean dark;

    private LocalDate date = LocalDate.now();
    private DaySnapshot snapshot = new DaySnapshot();
    private Listener listener;
    private ScrollState shared;
    private float hourHeight;
    private int scrollY;
    private float gutterWidth;
    private float contentLeft;
    private float contentRight;
    private float allDayHeight;
    private final List<EventInstance> allDay = new ArrayList<>();
    private final List<EventInstance> timed = new ArrayList<>();
    private final List<TimedLayout> layouts = new ArrayList<>();
    private final List<RectF> allDayHits = new ArrayList<>();
    private String[] hourLabels;
    private LocalDate nowDate;

    private float downX, downY, lastY;
    private float dragTouchMinute;
    private boolean moved;
    private boolean scrolling;
    private boolean dragActive;
    private int dragMode;
    private EventInstance pressedEvent;
    private RectF pressedRect;
    private EventInstance dragging;
    private int dragDuration;
    private int dragStartMinute;
    private int dragEndMinute;
    private int previewStartMinute;
    private int previewEndMinute;
    private int dragDayOffset;
    private boolean emptyLongPressFired;
    private final Runnable emptyLongPressRunnable = new Runnable() {
        @Override public void run() {
            if (pressedEvent != null || moved) return;
            emptyLongPressFired = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            int minute = minuteAt(downY);
            minute = Math.round(minute / (float) SNAP_MINUTES) * SNAP_MINUTES;
            minute = Math.max(0, Math.min(24 * 60 - 60, minute));
            long start = date.atTime(minute / 60, minute % 60).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            if (listener != null) listener.onEmptyLongPress(start);
        }
    };
    private final Runnable longPressRunnable = new Runnable() {
        @Override public void run() {
            if (pressedEvent == null || moved || pressedRect == null) return;
            dragging = pressedEvent;
            dragActive = true;
            long durationMillis = Math.max(SNAP_MINUTES * 60_000L, dragging.endAt - dragging.startAt);
            dragDuration = (int) Math.max(SNAP_MINUTES, durationMillis / 60_000L);
            ZoneId zone = TimeUtils.zoneOf(dragging.source);
            long dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli();
            dragStartMinute = clamp((int) ((dragging.startAt - dayStart) / 60_000L), 0, 24 * 60);
            dragEndMinute = clamp((int) ((dragging.endAt - dayStart) / 60_000L), 0, 24 * 60);
            dragEndMinute = clamp(Math.max(dragStartMinute + SNAP_MINUTES, dragEndMinute), 0, 24 * 60);
            previewStartMinute = dragStartMinute;
            previewEndMinute = dragStartMinute + dragDuration;
            dragDayOffset = 0;
            dragTouchMinute = minuteAt(downY);
            if (!crossesDate(dragging) && dragMode == 0 && pressedRect != null) {
                if (downY < pressedRect.top + dp(16)) dragMode = 1;
                else if (downY > pressedRect.bottom - dp(16)) dragMode = 2;
            }
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            ViewParent parent = getParent();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
            invalidate();
        }
    };
    private final Runnable minuteTicker = new Runnable() {
        @Override public void run() {
            if (date.equals(LocalDate.now())) invalidate();
            handler.postDelayed(this, 60_000L);
        }
    };

    public DayTimelineView(Context context) { this(context, null); }

    public DayTimelineView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        setFocusable(true);
        density = getResources().getDisplayMetrics().density;
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        minHourHeight = (int) dp(38);
        maxHourHeight = (int) dp(180);
        hourHeight = dp(64);
        gutterWidth = dp(57);
        contentLeft = gutterWidth + dp(7);
        contentRight = dp(8);
        scroller = new OverScroller(context);
        hourTextPaint.setTextSize(10f * getResources().getDisplayMetrics().scaledDensity);
        hourTextPaint.setTextAlign(Paint.Align.RIGHT);
        hourTextPaint.setColor(ContextCompat.getColor(context, R.color.text_secondary));
        int lineColor = ContextCompat.getColor(context, R.color.divider);
        linePaint.setColor(lineColor);
        linePaint.setStrokeWidth(Math.max(1f, density * 0.6f));
        halfLinePaint.setColor(EventPalette.withAlpha(lineColor, 90));
        halfLinePaint.setStrokeWidth(Math.max(0.5f, density * 0.5f));
        eventFillPaint.setStyle(Paint.Style.FILL);
        eventBarPaint.setStyle(Paint.Style.FILL);
        eventBorderPaint.setStyle(Paint.Style.STROKE);
        eventBorderPaint.setStrokeWidth(Math.max(1f, density * 0.6f));
        eventTitlePaint.setTextSize(12.5f * getResources().getDisplayMetrics().scaledDensity);
        eventTitlePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        eventTitlePaint.setColor(ContextCompat.getColor(context, R.color.text_primary));
        eventMetaPaint.setTextSize(10.3f * getResources().getDisplayMetrics().scaledDensity);
        eventMetaPaint.setColor(ContextCompat.getColor(context, R.color.text_secondary));
        nowPaint.setColor(ContextCompat.getColor(context, R.color.accent));
        nowPaint.setStrokeWidth(Math.max(1.2f, density));
        nowDotPaint.setStyle(Paint.Style.FILL);
        nowDotPaint.setColor(ContextCompat.getColor(context, R.color.accent));
        dragLabelPaint.setTextSize(10f * getResources().getDisplayMetrics().scaledDensity);
        dragLabelPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        dragLabelPaint.setTextAlign(Paint.Align.CENTER);
        dragLabelPaint.setColor(0xFFFFFFFF);
        dragLabelBgPaint.setColor(ContextCompat.getColor(context, R.color.accent));
        dragLabelBgPaint.setStyle(Paint.Style.FILL);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                cancelLongPresses();
                moved = true;
                scrolling = false;
                ViewParent parent = getParent();
                if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                return true;
            }

            @Override public boolean onScale(ScaleGestureDetector detector) {
                float focus = detector.getFocusY();
                float timelineFocus = focus - allDayHeight;
                if (timelineFocus < 0) return true;
                float minute = (scrollY + timelineFocus) / hourHeight * 60f;
                float next = hourHeight * detector.getScaleFactor();
                next = Math.max(minHourHeight, Math.min(maxHourHeight, next));
                if (Math.abs(next - hourHeight) < 0.5f) return true;
                hourHeight = next;
                int newScroll = Math.round(minute * hourHeight / 60f - timelineFocus);
                setTimelineScrollY(newScroll);
                updateShared();
                invalidate();
                return true;
            }
        });
        scaleDetector.setQuickScaleEnabled(false);
        hourLabels = buildHourLabels();
    }

    public void setListener(Listener listener) { this.listener = listener; }

    public void setScrollState(ScrollState state) {
        this.shared = state;
        if (state != null) {
            if (state.hourHeight > 0) hourHeight = Math.max(minHourHeight, Math.min(maxHourHeight, state.hourHeight));
            scrollY = 0;
        }
    }

    public void setDate(LocalDate date) {
        this.date = date == null ? LocalDate.now() : date;
        if (shared != null) scrollY = shared.scrollY;
        rebuild();
        invalidate();
    }

    public void setSnapshot(DaySnapshot snapshot) {
        this.snapshot = snapshot == null ? new DaySnapshot() : snapshot;
        rebuild();
        invalidate();
    }

    public void scrollToTime(int minutes) {
        if (shared != null) shared.hourHeight = hourHeight;
        setTimelineScrollY(Math.round(minutes * hourHeight / 60f));
        updateShared();
        invalidate();
    }

    private void rebuild() {
        timed.clear();
        allDay.clear();
        layouts.clear();
        List<EventInstance> events = snapshot.eventsFor(date);
        for (EventInstance event : events) {
            if (event.isAllDay()) allDay.add(event);
            else timed.add(event);
        }
        Collections.sort(timed, (a, b) -> {
            int c = Long.compare(a.startAt, b.startAt);
            return c != 0 ? c : Long.compare(b.endAt, a.endAt);
        });
        allDayHeight = allDay.isEmpty() ? 0 : dp(46);
        layoutTimed();
    }

    private void layoutTimed() {
        if (hourHeight <= 0) return;
        List<Span> spans = new ArrayList<>();
        for (EventInstance e : timed) {
            ZoneId eventZone = TimeUtils.zoneOf(e.source);
            long dayStart = date.atStartOfDay(eventZone).toInstant().toEpochMilli();
            long dayEnd = date.plusDays(1).atStartOfDay(eventZone).toInstant().toEpochMilli();
            int start = (int) Math.max(0, (Math.max(e.startAt, dayStart) - dayStart) / 60000L);
            int end = (int) Math.min(24 * 60, (Math.min(e.endAt, dayEnd) - dayStart) / 60000L);
            if (end <= start) end = Math.min(24 * 60, start + 15);
            start = clamp(start, 0, 24 * 60 - 1);
            end = clamp(end, start + 1, 24 * 60);
            spans.add(new Span(e, start, end));
        }
        Collections.sort(spans, (a, b) -> {
            int c = Integer.compare(a.start, b.start);
            return c != 0 ? c : Integer.compare(b.end, a.end);
        });
        float available = Math.max(dp(40), getWidth() - contentLeft - contentRight);
        int i = 0;
        while (i < spans.size()) {
            int j = i + 1;
            int clusterEnd = spans.get(i).end;
            while (j < spans.size() && spans.get(j).start < clusterEnd) {
                clusterEnd = Math.max(clusterEnd, spans.get(j).end);
                j++;
            }
            List<List<Integer>> columns = new ArrayList<>();
            int[] assigned = new int[j - i];
            for (int k = i; k < j; k++) {
                Span span = spans.get(k);
                int column = -1;
                for (int c = 0; c < columns.size(); c++) {
                    List<Integer> ends = columns.get(c);
                    if (ends.get(ends.size() - 1) <= span.start) {
                        column = c;
                        break;
                    }
                }
                if (column < 0) {
                    column = columns.size();
                    columns.add(new ArrayList<>());
                }
                columns.get(column).add(span.end);
                assigned[k - i] = column;
            }
            int columnsCount = Math.max(1, columns.size());
            float columnWidth = available / columnsCount;
            for (int k = i; k < j; k++) {
                Span span = spans.get(k);
                float left = contentLeft + assigned[k - i] * columnWidth;
                float right = left + columnWidth - dp(3);
                layouts.add(new TimedLayout(span.event, span.start, span.end, left, Math.max(left + dp(8), right)));
            }
            i = j;
        }
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutTimed();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (width <= 0 || height <= 0) return;
        allDayHits.clear();
        drawAllDay(canvas, width);
        float timelineHeight = height - allDayHeight;
        int maxScroll = Math.max(0, Math.round(24 * hourHeight - timelineHeight));
        if (scrollY > maxScroll) scrollY = maxScroll;
        if (shared != null) shared.scrollY = scrollY;

        canvas.save();
        canvas.clipRect(0, allDayHeight, width, height);
        drawHourLines(canvas, width);
        drawNowLine(canvas, width);
        for (TimedLayout layout : layouts) {
            if (dragActive && dragging != null && layout.event == dragging) continue;
            drawEvent(canvas, layout.event, layout.start, layout.end, layout.left, layout.right);
        }
        if (dragActive && dragging != null) {
            drawEvent(canvas, dragging, previewStartMinute, previewEndMinute, dragRectLeft(), dragRectRight());
            drawDragHandles(canvas);
            drawDayOffsetBadge(canvas, width);
        }
        canvas.restore();
    }

    private void drawHourLines(Canvas canvas, float width) {
        float top = allDayHeight - scrollY;
        for (int hour = 0; hour <= 24; hour++) {
            float y = top + hour * hourHeight;
            if (y < allDayHeight - dp(20) || y > getHeight() + dp(20)) continue;
            if (hour < 24) {
                float halfY = y + hourHeight / 2f;
                if (halfY >= allDayHeight && halfY <= getHeight()) {
                    canvas.drawLine(contentLeft, halfY, width - dp(8), halfY, halfLinePaint);
                }
            }
            if (y >= allDayHeight - dp(8)) {
                Paint.FontMetrics fm = hourTextPaint.getFontMetrics();
                float baseline = y - fm.ascent + dp(1);
                if (baseline < getHeight() - dp(2)) {
                    canvas.drawText(hourLabels[hour], gutterWidth - dp(4), baseline, hourTextPaint);
                }
            }
            if (hour < 24) canvas.drawLine(gutterWidth, y, width - dp(8), y, linePaint);
        }
    }

    private void drawAllDay(Canvas canvas, float width) {
        if (allDay.isEmpty()) return;
        float y = dp(7);
        float chipHeight = dp(28);
        float available = Math.max(dp(50), width - gutterWidth - dp(8));
        int show = Math.min(2, allDay.size());
        float chipWidth = (available - dp(4) * Math.max(0, show - 1)) / Math.max(1, show);
        for (int i = 0; i < show; i++) {
            EventInstance event = allDay.get(i);
            CalendarEntity calendar = snapshot.calendarFor(event.source.calendarId);
            String hex = event.source.color != null && EventPalette.isValid(event.source.color)
                    ? event.source.color : (calendar != null ? calendar.color : EventPalette.COLORS.get(0).hex);
            int color = EventPalette.parse(hex, nowDotPaint.getColor());
            float left = gutterWidth + i * (chipWidth + dp(4));
            float right = left + chipWidth;
            rect.set(left, y, right, y + chipHeight);
            eventFillPaint.setColor(EventPalette.withAlpha(color, dark ? 95 : 40));
            canvas.drawRoundRect(rect, dp(6), dp(6), eventFillPaint);
            eventBarPaint.setColor(color);
            rect.set(left, y, left + dp(3), y + chipHeight);
            canvas.drawRoundRect(rect, dp(2), dp(2), eventBarPaint);
            eventTitlePaint.setColor(ContextCompat.getColor(getContext(), R.color.text_primary));
            CharSequence title = TextUtils.ellipsize(event.source.title == null ? "" : event.source.title,
                    eventTitlePaint, chipWidth - dp(12), TextUtils.TruncateAt.END);
            Paint.FontMetrics fm = eventTitlePaint.getFontMetrics();
            canvas.drawText(title.toString(), left + dp(7), y + chipHeight / 2f - (fm.ascent + fm.descent) / 2f, eventTitlePaint);
            allDayHits.add(new RectF(left, y, right, y + chipHeight));
        }
        if (allDay.size() > show) {
            eventMetaPaint.setColor(ContextCompat.getColor(getContext(), R.color.text_secondary));
            canvas.drawText("+" + (allDay.size() - show), width - dp(9), y + chipHeight / 2f + dp(3), eventMetaPaint);
        }
    }

    private void drawEvent(Canvas canvas, EventInstance event, int startMinute, int endMinute, float left, float right) {
        float top = allDayHeight + startMinute * hourHeight / 60f - scrollY;
        float bottom = allDayHeight + endMinute * hourHeight / 60f - scrollY;
        if (bottom - top < dp(16)) bottom = top + dp(16);
        if (bottom < allDayHeight || top > getHeight()) return;
        CalendarEntity calendar = snapshot.calendarFor(event.source.calendarId);
        String hex = event.source.color != null && EventPalette.isValid(event.source.color)
                ? event.source.color : (calendar != null ? calendar.color : EventPalette.COLORS.get(0).hex);
        int color = EventPalette.parse(hex, nowDotPaint.getColor());
        boolean past = event.endAt < System.currentTimeMillis();
        int alpha = past ? 60 : 255;
        rect.set(left, top + dp(1), right, bottom - dp(1));
        eventFillPaint.setColor(EventPalette.withAlpha(color, past ? (dark ? 32 : 16) : (dark ? 70 : 32)));
        canvas.drawRoundRect(rect, dp(6), dp(6), eventFillPaint);
        eventBorderPaint.setColor(EventPalette.withAlpha(color, past ? 60 : 115));
        canvas.drawRoundRect(rect, dp(6), dp(6), eventBorderPaint);
        eventBarPaint.setColor(EventPalette.withAlpha(color, alpha));
        rect.set(left, top + dp(1), left + dp(3), bottom - dp(1));
        canvas.drawRoundRect(rect, dp(1.5f), dp(1.5f), eventBarPaint);
        float textLeft = left + dp(8);
        float textRight = right - dp(5);
        float textWidth = Math.max(0, textRight - textLeft);
        if (textWidth <= 0) return;
        eventTitlePaint.setColor(ContextCompat.getColor(getContext(), R.color.text_primary));
        eventTitlePaint.setStrikeThruText(event.source.completed);
        eventTitlePaint.setAlpha(past ? 150 : 255);
        float textTop = top + dp(4);
        Paint.FontMetrics fm = eventTitlePaint.getFontMetrics();
        float baseline = textTop - fm.ascent;
        if (baseline < bottom - dp(2)) {
            CharSequence title = TextUtils.ellipsize(event.source.title == null ? "" : event.source.title,
                    eventTitlePaint, textWidth, TextUtils.TruncateAt.END);
            canvas.drawText(title.toString(), textLeft, baseline, eventTitlePaint);
        }
        if (bottom - top > dp(31) && baseline + dp(12) < bottom) {
            eventMetaPaint.setColor(ContextCompat.getColor(getContext(), R.color.text_secondary));
            eventMetaPaint.setAlpha(past ? 130 : 255);
            String time = TimeUtils.formatTimeRange(event.startAt, event.endAt, ZoneId.systemDefault());
            CharSequence meta = TextUtils.ellipsize(time, eventMetaPaint, textWidth, TextUtils.TruncateAt.END);
            canvas.drawText(meta.toString(), textLeft, baseline + dp(13), eventMetaPaint);
            if (bottom - top > dp(48) && event.source.location != null && !event.source.location.trim().isEmpty()) {
                CharSequence loc = TextUtils.ellipsize(event.source.location, eventMetaPaint, textWidth, TextUtils.TruncateAt.END);
                canvas.drawText(loc.toString(), textLeft, baseline + dp(25), eventMetaPaint);
            }
        }
        eventTitlePaint.setStrikeThruText(false);
        eventTitlePaint.setAlpha(255);
    }

    private void drawNowLine(Canvas canvas, float width) {
        if (!date.equals(LocalDate.now())) return;
        LocalTime now = LocalTime.now();
        float minute = now.getHour() * 60f + now.getMinute() + now.getSecond() / 60f;
        float y = allDayHeight + minute * hourHeight / 60f - scrollY;
        if (y < allDayHeight || y > getHeight()) return;
        float left = gutterWidth + dp(2);
        canvas.drawLine(left, y, width - dp(8), y, nowPaint);
        canvas.drawCircle(left, y, dp(3.3f), nowDotPaint);
    }

    private void drawDragHandles(Canvas canvas) {
        float top = allDayHeight + previewStartMinute * hourHeight / 60f - scrollY;
        float bottom = allDayHeight + previewEndMinute * hourHeight / 60f - scrollY;
        float left = dragRectLeft();
        float right = dragRectRight();
        nowPaint.setAlpha(210);
        canvas.drawLine(left + dp(5), top + dp(5), right - dp(5), top + dp(5), nowPaint);
        canvas.drawLine(left + dp(5), bottom - dp(5), right - dp(5), bottom - dp(5), nowPaint);
        nowPaint.setAlpha(255);
    }

    private void drawDayOffsetBadge(Canvas canvas, float width) {
        if (dragDayOffset == 0) return;
        String label = dragDayOffset > 0 ? "+" + dragDayOffset + "d" : dragDayOffset + "d";
        float textWidth = dragLabelPaint.measureText(label);
        float cx = width - dp(8) - textWidth / 2f - dp(10);
        float cy = allDayHeight + dp(18);
        rect.set(cx - textWidth / 2f - dp(8), cy - dp(10), cx + textWidth / 2f + dp(8), cy + dp(10));
        canvas.drawRoundRect(rect, dp(10), dp(10), dragLabelBgPaint);
        Paint.FontMetrics fm = dragLabelPaint.getFontMetrics();
        canvas.drawText(label, cx, cy - (fm.ascent + fm.descent) / 2f, dragLabelPaint);
    }

    private float dragRectLeft() {
        int index = -1;
        for (int i = 0; i < layouts.size(); i++) if (layouts.get(i).event == dragging) { index = i; break; }
        if (index >= 0) return layouts.get(index).left;
        return contentLeft;
    }

    private float dragRectRight() {
        for (TimedLayout layout : layouts) if (layout.event == dragging) return layout.right;
        return getWidth() - contentRight;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getPointerCount() > 1) {
            cancelLongPresses();
            scaleDetector.onTouchEvent(event);
            return true;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (shared != null && shared.hourHeight != hourHeight) shared.hourHeight = hourHeight;
                downX = event.getX();
                downY = event.getY();
                lastY = downY;
                moved = false;
                scrolling = false;
                dragActive = false;
                emptyLongPressFired = false;
                scroller.forceFinished(true);
                if (velocityTracker != null) velocityTracker.recycle();
                velocityTracker = VelocityTracker.obtain();
                velocityTracker.addMovement(event);
                pressedEvent = findEventAt(event.getX(), event.getY());
                pressedRect = findEventRect(pressedEvent);
                dragMode = 0;
                handler.removeCallbacks(longPressRunnable);
                handler.removeCallbacks(emptyLongPressRunnable);
                if (pressedEvent != null && !isOnAllDay(event.getY()) && pressedRect != null) {
                    handler.postDelayed(longPressRunnable, 240);
                } else if (!isOnAllDay(event.getY())) {
                    handler.postDelayed(emptyLongPressRunnable, 480);
                }
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                cancelLongPresses();
                dragActive = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (velocityTracker != null) velocityTracker.addMovement(event);
                if (dragActive) {
                    updateDrag(event.getX(), event.getY());
                    autoScrollWhileDragging(event.getY());
                    return true;
                }
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (!moved && Math.abs(dx) + Math.abs(dy) > touchSlop) {
                    moved = true;
                    cancelLongPresses();
                }
                if (moved) {
                    if (Math.abs(dy) > Math.abs(dx)) {
                        ViewParent parent = getParent();
                        if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                        scrolling = true;
                        float delta = lastY - event.getY();
                        if (Math.abs(delta) > 0.1f) {
                            setTimelineScrollY(scrollY + Math.round(delta));
                            updateShared();
                            invalidate();
                        }
                        lastY = event.getY();
                    } else {
                        ViewParent parent = getParent();
                        if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                cancelLongPresses();
                if (dragActive) {
                    commitDrag();
                    return true;
                }
                if (!moved && !emptyLongPressFired) {
                    EventInstance allDayEvent = findEventAt(event.getX(), event.getY());
                    if (allDayEvent != null && listener != null) {
                        listener.onEventClick(allDayEvent);
                        performClick();
                    }
                }
                if (scrolling && velocityTracker != null && !moved) {
                    // no fling
                }
                if (scrolling && velocityTracker != null) {
                    velocityTracker.computeCurrentVelocity(1000);
                    float vy = velocityTracker.getYVelocity();
                    if (Math.abs(vy) > dp(120)) {
                        scroller.fling(0, scrollY, 0, Math.round(-vy), 0, 0, 0, maxScroll());
                        postInvalidateOnAnimation();
                    }
                }
                resetTouch();
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancelLongPresses();
                dragActive = false;
                dragging = null;
                resetTouch();
                invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    @Override public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            setTimelineScrollY(scroller.getCurrY());
            updateShared();
            invalidate();
        }
    }

    private void updateDrag(float x, float y) {
        if (dragging == null) return;
        float minute = minuteAt(y);
        int delta = Math.round((minute - dragTouchMinute) / (float) SNAP_MINUTES) * SNAP_MINUTES;
        if (dragMode == 0) {
            int duration = dragDuration;
            int start = clamp(dragStartMinute + delta, 0, 24 * 60 - SNAP_MINUTES);
            previewStartMinute = start;
            previewEndMinute = start + duration;
            dragDayOffset = clamp(Math.round((x - downX) / (getWidth() * 0.55f)), -7, 7);
        } else if (dragMode == 1) {
            int start = clamp(dragStartMinute + delta, 0, dragEndMinute - SNAP_MINUTES);
            start = Math.round(start / (float) SNAP_MINUTES) * SNAP_MINUTES;
            previewStartMinute = start;
            previewEndMinute = dragEndMinute;
            dragDayOffset = 0;
        } else {
            int end = clamp(dragEndMinute + delta, dragStartMinute + SNAP_MINUTES, 24 * 60);
            end = Math.round(end / (float) SNAP_MINUTES) * SNAP_MINUTES;
            previewStartMinute = dragStartMinute;
            previewEndMinute = end;
            dragDayOffset = 0;
        }
        invalidate();
    }

    private void autoScrollWhileDragging(float y) {
        lastDragY = y;
        if (!autoScrollScheduled) {
            autoScrollScheduled = true;
            postOnAnimation(autoScrollRunnable);
        }
    }

    private float lastDragY;
    private boolean autoScrollScheduled;
    private final Runnable autoScrollRunnable = new Runnable() {
        @Override public void run() {
            autoScrollScheduled = false;
            if (!dragActive) return;
            float edge = dp(54);
            int delta = 0;
            if (lastDragY < allDayHeight + edge) delta = -Math.round(dp(7));
            else if (lastDragY > getHeight() - edge) delta = Math.round(dp(7));
            if (delta != 0 && scrollY + delta >= 0 && scrollY + delta <= maxScroll()) {
                setTimelineScrollY(scrollY + delta);
                updateShared();
                invalidate();
                autoScrollScheduled = true;
                postOnAnimation(this);
            }
        }
    };

    private void commitDrag() {
        if (dragging == null) {
            resetTouch();
            return;
        }
        EventInstance event = dragging;
        boolean resized = dragMode != 0;
        ZoneId zone = ZoneId.systemDefault();
        LocalDate target = date.plusDays(dragDayOffset);
        long newStart;
        long newEnd;
        if (dragMode == 1) {
            newStart = target.atTime(previewStartMinute / 60, previewStartMinute % 60).atZone(zone).toInstant().toEpochMilli();
            newEnd = target.atTime(previewEndMinute / 60, previewEndMinute % 60).atZone(zone).toInstant().toEpochMilli();
            if (newEnd <= newStart) newEnd = newStart + SNAP_MINUTES * 60000L;
        } else {
            long base = target.atStartOfDay(zone).toInstant().toEpochMilli();
            newStart = base + previewStartMinute * 60000L;
            newEnd = base + previewEndMinute * 60000L;
            if (newEnd <= newStart) newEnd = newStart + SNAP_MINUTES * 60000L;
        }
        dragActive = false;
        dragging = null;
        resetTouch();
        invalidate();
        if (listener != null) listener.onEventMoved(event, newStart, newEnd, resized);
    }

    private void resetTouch() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
        pressedEvent = null;
        pressedRect = null;
        moved = false;
        scrolling = false;
    }

    private void cancelLongPresses() {
        handler.removeCallbacks(longPressRunnable);
        handler.removeCallbacks(emptyLongPressRunnable);
    }

    private EventInstance findEventAt(float x, float y) {
        if (isOnAllDay(y)) {
            for (int i = 0; i < allDayHits.size() && i < allDay.size(); i++) {
                if (allDayHits.get(i).contains(x, y)) return allDay.get(i);
            }
            return null;
        }
        for (int i = layouts.size() - 1; i >= 0; i--) {
            TimedLayout layout = layouts.get(i);
            float top = allDayHeight + layout.start * hourHeight / 60f - scrollY;
            float bottom = allDayHeight + layout.end * hourHeight / 60f - scrollY;
            if (bottom - top < dp(16)) bottom = top + dp(16);
            if (x >= layout.left && x <= layout.right && y >= top && y <= bottom) return layout.event;
        }
        return null;
    }

    private RectF findEventRect(EventInstance event) {
        if (event == null) return null;
        for (TimedLayout layout : layouts) {
            if (layout.event == event) {
                float top = allDayHeight + layout.start * hourHeight / 60f - scrollY;
                float bottom = allDayHeight + layout.end * hourHeight / 60f - scrollY;
                return new RectF(layout.left, top, layout.right, Math.max(bottom, top + dp(16)));
            }
        }
        return null;
    }

    private boolean isOnAllDay(float y) { return allDayHeight > 0 && y < allDayHeight; }

    private boolean crossesDate(EventInstance event) {
        ZoneId zone = TimeUtils.zoneOf(event.source);
        LocalDate start = TimeUtils.dateOf(event.startAt, zone);
        LocalDate end = TimeUtils.dateOf(Math.max(event.startAt, event.endAt - 1), zone);
        return !start.equals(end);
    }

    private int minuteAt(float viewY) {
        if (hourHeight <= 0) return 0;
        return Math.round((viewY - allDayHeight + scrollY) / hourHeight * 60f);
    }

    private int minuteOf(long epochMillis) {
        return TimeUtils.minutesOfDay(epochMillis, ZoneId.systemDefault());
    }

    private void setTimelineScrollY(int value) {
        int max = maxScroll();
        scrollY = Math.max(0, Math.min(max, value));
    }

    private int maxScroll() {
        int timelineHeight = Math.max(0, getHeight() - Math.round(allDayHeight));
        return Math.max(0, Math.round(24 * hourHeight) - timelineHeight);
    }

    private void updateShared() {
        if (shared != null) {
            shared.scrollY = scrollY;
            shared.hourHeight = hourHeight;
        }
    }

    private String[] buildHourLabels() {
        String[] labels = new String[25];
        DateTimeFormatter formatter = TimeUtils.is24Hour()
                ? DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
                : DateTimeFormatter.ofPattern("h a", Locale.getDefault());
        for (int h = 0; h <= 24; h++) {
            if (h == 24) labels[h] = "";
            else labels[h] = LocalTime.of(h, 0).format(formatter);
        }
        return labels;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        handler.postDelayed(minuteTicker, 30_000L);
    }

    @Override protected void onDetachedFromWindow() {
        handler.removeCallbacks(minuteTicker);
        cancelLongPresses();
        super.onDetachedFromWindow();
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    private float dp(float value) { return value * density; }

    private static final class Span {
        final EventInstance event;
        final int start;
        final int end;
        Span(EventInstance event, int start, int end) {
            this.event = event; this.start = start; this.end = end;
        }
    }

    private static final class TimedLayout {
        final EventInstance event;
        final int start;
        final int end;
        final float left;
        final float right;
        TimedLayout(EventInstance event, int start, int end, float left, float right) {
            this.event = event; this.start = start; this.end = end; this.left = left; this.right = right;
        }
    }
}
