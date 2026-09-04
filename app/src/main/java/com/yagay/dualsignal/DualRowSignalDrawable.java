package com.yagay.dualsignal;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * iOS-style dual-row cellular signal icon (2 rows × 4 rounded bars).
 * Top row = SIM1 level (0–4), bottom row = SIM2 level (0–4).
 * Geometry inspired by the ColorOS "iOS27 dual signal" overlay vectors (s0–s4).
 */
public final class DualRowSignalDrawable extends Drawable {
    private static final int BAR_COUNT = 4;
    private static final float VIEW_W = 20f;
    private static final float VIEW_H = 14f;
    /** Bar center X in viewport units (approx. from reference s0.xml). */
    private static final float[] BAR_X = {1.54f, 6.58f, 11.62f, 16.66f};
    private static final float BAR_W = 3.1f;
    private static final float TOP_Y0 = 1.2f;
    private static final float TOP_Y1 = 6.3f;
    private static final float BOT_Y0 = 7.7f;
    private static final float BOT_Y1 = 12.8f;
    private static final float CORNER = 1.35f;

    private final Paint filled = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint empty = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();

    private int levelTop = 0;
    private int levelBottom = 0;
    private int alpha = 255;

    public DualRowSignalDrawable() {
        filled.setStyle(Paint.Style.FILL);
        filled.setColor(0xFFFFFFFF);
        empty.setStyle(Paint.Style.FILL);
        empty.setColor(0x66FFFFFF);
    }

    public void setLevels(int top, int bottom) {
        top = clamp(top);
        bottom = clamp(bottom);
        if (top == levelTop && bottom == levelBottom) return;
        levelTop = top;
        levelBottom = bottom;
        invalidateSelf();
    }

    public int getLevelTop() {
        return levelTop;
    }

    public int getLevelBottom() {
        return levelBottom;
    }

    private static int clamp(int v) {
        if (v < 0) return 0;
        if (v > BAR_COUNT) return BAR_COUNT;
        return v;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;

        float sx = b.width() / VIEW_W;
        float sy = b.height() / VIEW_H;
        float s = Math.min(sx, sy);
        float ox = b.left + (b.width() - VIEW_W * s) / 2f;
        float oy = b.top + (b.height() - VIEW_H * s) / 2f;

        int a = alpha;
        filled.setAlpha(a);
        empty.setAlpha(Math.max(0, (int) (a * 0.40f)));

        drawRow(canvas, ox, oy, s, TOP_Y0, TOP_Y1, levelTop);
        drawRow(canvas, ox, oy, s, BOT_Y0, BOT_Y1, levelBottom);
    }

    private void drawRow(Canvas canvas, float ox, float oy, float s,
                         float y0, float y1, int level) {
        float h = (y1 - y0) * s;
        float corner = CORNER * s;
        for (int i = 0; i < BAR_COUNT; i++) {
            float cx = BAR_X[i];
            float left = ox + (cx - BAR_W / 2f) * s;
            float top = oy + y0 * s;
            // Rising bars like classic cellular: each bar taller than the previous.
            float barH = h * (0.45f + 0.55f * (i + 1) / (float) BAR_COUNT);
            float barTop = top + (h - barH);
            tmp.set(left, barTop, left + BAR_W * s, top + h);
            canvas.drawRoundRect(tmp, corner, corner, i < level ? filled : empty);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        filled.setColorFilter(colorFilter);
        empty.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return 24;
    }

    @Override
    public int getIntrinsicHeight() {
        return 17;
    }

    /** Map Android ImageView / LevelList level (often 0–4 or 0–10000) to 0–4 bars. */
    public static int normalizeLevel(int raw) {
        if (raw < 0) return 0;
        if (raw <= 4) return raw;
        if (raw <= 5) return Math.min(4, raw); // some ROMs use 0–5
        // LevelListDrawable style 0–10000
        if (raw <= 10000) {
            return Math.min(4, Math.max(0, (raw * 4 + 5000) / 10000));
        }
        return 4;
    }
}
