package com.dynamicagence.icontroller;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Pictogrammes au trait (manette, volant, chevron), dessinés en vectoriel. */
final class Glyph extends View {
    static final int PAD = 0, WHEEL = 1, CHEVRON = 2;

    private final int kind;
    private int color;
    private final Paint s = new Paint(Paint.ANTI_ALIAS_FLAG);

    Glyph(Context c, int kind, int color) {
        super(c);
        this.kind = kind;
        this.color = color;
        s.setStyle(Paint.Style.STROKE);
        s.setStrokeCap(Paint.Cap.ROUND);
        s.setStrokeJoin(Paint.Join.ROUND);
    }

    void setColor(int c) { color = c; invalidate(); }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), m = Math.min(w, h);
        float cx = w / 2f, cy = h / 2f;
        s.setColor(color);
        s.setStrokeWidth(Math.max(2f, m * 0.065f));
        if (kind == PAD) {
            float a = m * 0.46f;
            Path p = new Path();
            p.moveTo(cx - a * 0.55f, cy - a * 0.5f);
            p.lineTo(cx + a * 0.55f, cy - a * 0.5f);
            p.cubicTo(cx + a * 1.05f, cy - a * 0.5f, cx + a * 1.12f, cy + a * 0.55f, cx + a * 0.95f, cy + a * 0.72f);
            p.cubicTo(cx + a * 0.8f, cy + a * 0.88f, cx + a * 0.5f, cy + a * 0.7f, cx + a * 0.32f, cy + a * 0.38f);
            p.lineTo(cx - a * 0.32f, cy + a * 0.38f);
            p.cubicTo(cx - a * 0.5f, cy + a * 0.7f, cx - a * 0.8f, cy + a * 0.88f, cx - a * 0.95f, cy + a * 0.72f);
            p.cubicTo(cx - a * 1.12f, cy + a * 0.55f, cx - a * 1.05f, cy - a * 0.5f, cx - a * 0.55f, cy - a * 0.5f);
            p.close();
            c.drawPath(p, s);
            float d = a * 0.3f;
            c.drawLine(cx - a * 0.55f - d * 0.5f, cy, cx - a * 0.55f + d * 0.5f, cy, s);
            c.drawLine(cx - a * 0.55f, cy - d * 0.5f, cx - a * 0.55f, cy + d * 0.5f, s);
            s.setStyle(Paint.Style.FILL);
            c.drawCircle(cx + a * 0.5f, cy - a * 0.08f, m * 0.035f, s);
            c.drawCircle(cx + a * 0.72f, cy + a * 0.1f, m * 0.035f, s);
            s.setStyle(Paint.Style.STROKE);
        } else if (kind == WHEEL) {
            float r = m * 0.4f;
            c.drawCircle(cx, cy, r, s);
            c.drawCircle(cx, cy, r * 0.2f, s);
            c.drawLine(cx - r, cy, cx - r * 0.2f, cy, s);
            c.drawLine(cx + r * 0.2f, cy, cx + r, cy, s);
            c.drawLine(cx, cy + r * 0.2f, cx, cy + r, s);
        } else {
            float q = m * 0.22f;
            Path p = new Path();
            p.moveTo(cx - q * 0.5f, cy - q);
            p.lineTo(cx + q * 0.6f, cy);
            p.lineTo(cx - q * 0.5f, cy + q);
            c.drawPath(p, s);
        }
    }
}
