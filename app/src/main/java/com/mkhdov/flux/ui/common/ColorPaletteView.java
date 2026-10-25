package com.mkhdov.flux.ui.common;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.mkhdov.flux.util.EventPalette;

public class ColorPaletteView extends View {
    public interface OnColorSelectedListener { void onColorSelected(String hex); }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringOuter = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String selected = EventPalette.COLORS.get(0).hex;
    private OnColorSelectedListener listener;
    private final RectF rect = new RectF();

    public ColorPaletteView(Context context) { this(context, null); }
    public ColorPaletteView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(dp(1.6f));
        ringOuter.setStyle(Paint.Style.STROKE);
        ringOuter.setStrokeWidth(dp(1.2f));
        setClickable(true);
    }

    public void setSelectedColor(String color) {
        if (color == null) return;
        selected = EventPalette.normalize(color);
        invalidate();
    }

    public String getSelectedColor() { return selected; }
    public void setOnColorSelectedListener(OnColorSelectedListener listener) { this.listener = listener; }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int desired = (int) (2 * dp(42) + dp(4));
        int height = resolveSize(desired, heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cell = getWidth() / 7f;
        float radius = Math.min(cell * 0.34f, dp(14));
        float rowHeight = getHeight() / 2f;
        for (int i = 0; i < EventPalette.COLORS.size(); i++) {
            int row = i / 7;
            int col = i % 7;
            float cx = col * cell + cell / 2f;
            float cy = row * rowHeight + rowHeight / 2f;
            int color = EventPalette.parse(EventPalette.COLORS.get(i).hex, Color.GRAY);
            fill.setColor(color);
            canvas.drawCircle(cx, cy, radius, fill);
            if (EventPalette.COLORS.get(i).hex.equalsIgnoreCase(selected)) {
                ring.setColor(color);
                ring.setAlpha(150);
                canvas.drawCircle(cx, cy, radius + dp(3.2f), ring);
                ringOuter.setColor(Color.WHITE);
                ringOuter.setAlpha(230);
                canvas.drawCircle(cx, cy, radius + dp(1.2f), ringOuter);
            }
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            float cell = getWidth() / 7f;
            int col = (int) (event.getX() / cell);
            int row = event.getY() > getHeight() / 2f ? 1 : 0;
            int index = row * 7 + col;
            if (index >= 0 && index < EventPalette.COLORS.size()) {
                selected = EventPalette.COLORS.get(index).hex;
                invalidate();
                if (listener != null) listener.onColorSelected(selected);
                performClick();
            }
            return true;
        }
        return true;
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
}
