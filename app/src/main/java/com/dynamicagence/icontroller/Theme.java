package com.dynamicagence.icontroller;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;

/**
 * Direction artistique commune : noir profond, surfaces mates, filets fins,
 * un seul accent bleu (ambre en voiture), typographie sans empattement nette.
 * Aucun dégradé décoratif, aucune ombre portée : l'état se lit par la couleur et la taille.
 */
final class Theme {
    private Theme() { }

    static final int BG = Color.rgb(10, 10, 11);
    static final int WELL = Color.rgb(17, 17, 19);
    static final int SURFACE = Color.rgb(28, 28, 30);
    static final int SURFACE_HI = Color.rgb(44, 44, 46);
    static final int HAIR = Color.argb(36, 255, 255, 255);
    static final int TEXT = Color.rgb(245, 245, 247);
    static final int DIM = Color.rgb(142, 142, 147);
    static final int FAINT = Color.rgb(72, 72, 74);

    static final int BLUE = Color.rgb(41, 151, 255);
    static final int AMBER = Color.rgb(255, 159, 10);
    static final int GREEN = Color.rgb(48, 209, 88);
    static final int RED = Color.rgb(255, 69, 58);

    // Touches Xbox, légèrement désaturées
    static final int KA = Color.rgb(50, 215, 75);
    static final int KB = Color.rgb(255, 69, 58);
    static final int KX = Color.rgb(10, 132, 255);
    static final int KY = Color.rgb(255, 214, 10);

    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    static final Typeface BOLD = Typeface.create("sans-serif-medium", Typeface.BOLD);

    static int alpha(int c, int a) { return Color.argb(a, Color.red(c), Color.green(c), Color.blue(c)); }

    /** Couleur d'une touche de manette : Xbox pour A/B/X/Y, gris clair sinon. */
    static int keyColor(int target) {
        switch (target) {
            case HidGamepad.BTN_A: return KA;
            case HidGamepad.BTN_B: return KB;
            case HidGamepad.BTN_X: return KX;
            case HidGamepad.BTN_Y: return KY;
            default: return Color.rgb(200, 200, 206);
        }
    }

    /**
     * Pastille d'état en haut : point (vert = connecté, ambre qui bat = en attente) + texte.
     * {@code t} est réglé ici ; {@code beat} = vrai pour la phase claire du clignotement.
     */
    static void statusPill(Canvas c, Paint p, Paint s, TextPaint t, String status, boolean connected,
                           boolean beat, float cx, float cy, float size, float maxW) {
        if (status == null || status.isEmpty()) status = connected ? "Connecté" : "En attente";
        t.setTypeface(REGULAR);
        t.setTextSize(size);
        t.setLetterSpacing(0f);
        CharSequence line = android.text.TextUtils.ellipsize(status, t, maxW, android.text.TextUtils.TruncateAt.END);
        float tw = t.measureText(line, 0, line.length());
        float h = size * 2.1f, dot = size * 0.34f, pad = size * 0.95f;
        float w = pad + dot * 2 + size * 0.6f + tw + pad;
        RectF r = new RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        p.setShader(null);
        p.setColor(SURFACE);
        c.drawRoundRect(r, h / 2, h / 2, p);
        s.setShader(null);
        s.setStyle(Paint.Style.STROKE);
        s.setStrokeWidth(Math.max(1f, size * 0.07f));
        s.setColor(HAIR);
        c.drawRoundRect(r, h / 2, h / 2, s);
        int col = connected ? GREEN : AMBER;
        p.setColor(connected || beat ? col : alpha(col, 70));
        c.drawCircle(r.left + pad + dot, cy, dot, p);
        t.setColor(connected ? TEXT : DIM);
        t.setTextAlign(Paint.Align.LEFT);
        c.drawText(line, 0, line.length(), r.left + pad + dot * 2 + size * 0.6f,
                cy - (t.ascent() + t.descent()) / 2f, t);
        t.setTextAlign(Paint.Align.CENTER);
    }

    /** Aplat arrondi avec filet, et ondulation au toucher (retour visuel immédiat). */
    static Drawable surface(int fill, int stroke, float radiusPx, float strokePx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radiusPx);
        if (strokePx > 0) g.setStroke(Math.max(1, Math.round(strokePx)), stroke);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(radiusPx);
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(46, 255, 255, 255)), g, mask);
    }
}
