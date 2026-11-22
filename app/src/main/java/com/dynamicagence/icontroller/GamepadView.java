package com.dynamicagence.icontroller;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Manette tactile façon DualShock 3 (corps noir laqué, sticks symétriques en bas,
 * croix en 4 flèches, voyants 1-4) avec la disposition et les couleurs de touches Xbox.
 */
public class GamepadView extends View {

    public interface MenuListener { void onMenu(); }

    // Palette
    private static final int GLOW = Color.rgb(127, 184, 255);
    private static final int LABEL = Color.rgb(200, 204, 212);
    private static final int LABEL_DIM = Color.rgb(120, 126, 138);
    private static final int LED_ON = Color.rgb(255, 59, 48);
    private static final int LED_OFF = Color.rgb(48, 22, 22);
    private static final int A_COL = Color.rgb(96, 196, 46);
    private static final int B_COL = Color.rgb(230, 62, 66);
    private static final int X_COL = Color.rgb(54, 132, 230);
    private static final int Y_COL = Color.rgb(242, 196, 30);

    private final HidGamepad pad;
    private MenuListener menuListener;
    private String status = "";
    private boolean connected;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint s = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint t = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface bold = Typeface.create("sans-serif-medium", Typeface.BOLD);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);

    private static final boolean DEBUG_HITS = false; // dessine les zones tactiles (mise au point)

    private Bitmap body;   // coque pré-rendue, transparente autour (perf sur vieux téléphone)
    private Shader bgShader;
    private float u;       // unité de taille

    // ================= Contrôles =================

    private abstract class Control {
        abstract boolean hit(float x, float y);
        abstract void down(float x, float y);
        abstract void move(float x, float y);
        abstract void up();
        abstract void draw(Canvas c);
    }

    private static final int FACE = 0, SHOULDER = 1, SMALL = 2;

    private class Btn extends Control {
        final int bit, kind, color; final String label;
        final RectF r = new RectF();
        boolean pressed;
        Shader shade;
        Btn(int bit, int kind, String label, int color) {
            this.bit = bit; this.kind = kind; this.label = label; this.color = color;
        }
        boolean hit(float x, float y) {
            if (kind == SHOULDER) // seulement la partie visible au-dessus de la coque
                return x >= r.left - u * 0.015f && x <= r.right + u * 0.015f && y <= r.top + u * 0.17f;
            if (kind == FACE) { // toute la cuvette compte : le bouton le plus proche l'emporte
                if (Math.hypot(x - faceCx, y - faceCy) > u * 0.215f) return false;
                float d = (float) Math.hypot(x - r.centerX(), y - r.centerY());
                for (Btn o : faces)
                    if (o != this && Math.hypot(x - o.r.centerX(), y - o.r.centerY()) < d) return false;
                return true;
            }
            float px = Math.max(r.width() * 0.3f, u * 0.02f), py = Math.max(r.height() * 0.3f, u * 0.02f);
            return x >= r.left - px && x <= r.right + px && y >= r.top - py && y <= r.bottom + py;
        }
        void down(float x, float y) { set(true); }
        void move(float x, float y) { }
        void up() { set(false); }
        void set(boolean on) {
            if (on == pressed) return;
            pressed = on;
            if (bit >= 0) pad.setButton(bit, on);
            else if (on && menuListener != null) menuListener.onMenu();
            if (on) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            invalidate();
        }
        void draw(Canvas c) {
            if (kind == FACE) drawFace(c, this);
            else if (kind == SHOULDER) drawShoulder(c, this);
            else drawSmall(c, this);
        }
    }

    private class Stick extends Control {
        final boolean left; float cx, cy, range; float kx, ky; boolean active;
        Stick(boolean left) { this.left = left; }
        boolean hit(float x, float y) { return Math.hypot(x - cx, y - cy) < range * 1.45f; }
        void down(float x, float y) { active = true; move(x, y); }
        void move(float x, float y) {
            float dx = (x - cx) / range, dy = (y - cy) / range;
            float m = (float) Math.hypot(dx, dy);
            if (m > 1f) { dx /= m; dy /= m; }
            if (m < 0.08f) { dx = 0; dy = 0; }
            kx = dx; ky = dy;
            if (left) pad.setLeftStick(dx, dy); else pad.setRightStick(dx, dy);
            invalidate();
        }
        void up() {
            active = false; kx = 0; ky = 0;
            if (left) pad.setLeftStick(0, 0); else pad.setRightStick(0, 0);
            invalidate();
        }
        void draw(Canvas c) { drawStick(c, this); }
    }

    private class DPad extends Control {
        float cx, cy, arm; int hat = HidGamepad.HAT_CENTER;
        boolean hit(float x, float y) { return Math.hypot(x - cx, y - cy) < arm * 1.45f; }
        void down(float x, float y) { move(x, y); }
        void move(float x, float y) {
            float dx = x - cx, dy = y - cy;
            int h;
            if (Math.hypot(dx, dy) < arm * 0.2f) h = HidGamepad.HAT_CENTER;
            else {
                double deg = Math.toDegrees(Math.atan2(dx, -dy));
                if (deg < 0) deg += 360;
                h = (int) Math.round(deg / 45.0) % 8;
            }
            if (h != hat) {
                hat = h;
                pad.setHat(h);
                if (h != HidGamepad.HAT_CENTER) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                invalidate();
            }
        }
        void up() { if (hat != HidGamepad.HAT_CENTER) { hat = HidGamepad.HAT_CENTER; pad.setHat(hat); invalidate(); } }
        boolean on(int dir) {
            if (hat == HidGamepad.HAT_CENTER) return false;
            int d = Math.abs(hat - dir); d = Math.min(d, 8 - d);
            return d <= 1;
        }
        void draw(Canvas c) { drawDPad(c, this); }
    }

    private final List<Control> controls = new ArrayList<>();
    private final SparseArray<Control> owners = new SparseArray<>();

    private final Btn bA = new Btn(HidGamepad.BTN_A, FACE, "A", A_COL);
    private final Btn bB = new Btn(HidGamepad.BTN_B, FACE, "B", B_COL);
    private final Btn bX = new Btn(HidGamepad.BTN_X, FACE, "X", X_COL);
    private final Btn bY = new Btn(HidGamepad.BTN_Y, FACE, "Y", Y_COL);
    private final Btn bLB = new Btn(HidGamepad.BTN_L1, SHOULDER, "LB", 0);
    private final Btn bLT = new Btn(HidGamepad.BTN_L2, SHOULDER, "LT", 0);
    private final Btn bRB = new Btn(HidGamepad.BTN_R1, SHOULDER, "RB", 0);
    private final Btn bRT = new Btn(HidGamepad.BTN_R2, SHOULDER, "RT", 0);
    private final Btn bLS = new Btn(HidGamepad.BTN_L3, SMALL, "LS", 0);
    private final Btn bRS = new Btn(HidGamepad.BTN_R3, SMALL, "RS", 0);
    private final Btn bView = new Btn(HidGamepad.BTN_SELECT, SMALL, "view", 0);
    private final Btn bMenuKey = new Btn(HidGamepad.BTN_START, SMALL, "menu", 0);
    private final Btn bHome = new Btn(HidGamepad.BTN_MODE, SMALL, "home", 0);
    private final Btn bGear = new Btn(-1, SMALL, "gear", 0);
    private final Stick left = new Stick(true);
    private final Stick right = new Stick(false);
    private final DPad dpad = new DPad();
    private final Btn[] shoulders = {bLT, bLB, bRB, bRT};
    private final Btn[] faces = {bA, bB, bX, bY};

    private float ledX, ledY, faceCx, faceCy;

    public GamepadView(Context ctx, HidGamepad pad) {
        super(ctx);
        this.pad = pad;
        t.setTextAlign(Paint.Align.CENTER);
        // Ordre de priorité au toucher : petits boutons d'abord, grandes zones à la fin.
        controls.add(bGear); controls.add(bView); controls.add(bMenuKey); controls.add(bHome);
        controls.add(bLB); controls.add(bLT); controls.add(bRB); controls.add(bRT);
        controls.add(bA); controls.add(bB); controls.add(bX); controls.add(bY);
        controls.add(bLS); controls.add(bRS);
        controls.add(dpad); controls.add(left); controls.add(right);
    }

    public void setMenuListener(MenuListener l) { menuListener = l; }
    public void setStatus(String st) { status = st != null ? st : ""; invalidate(); }
    public void setConnected(boolean c) { connected = c; invalidate(); }

    // ================= Mise en page =================

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (w == 0 || h == 0) return;
        u = Math.min(h, w * 0.5f);

        // Gâchettes / bumpers : glissés derrière le haut du corps
        bLT.r.set(w * 0.045f, h * 0.005f, w * 0.178f, h * 0.24f);
        bLB.r.set(w * 0.19f, h * 0.025f, w * 0.335f, h * 0.22f);
        bRB.r.set(w * 0.665f, h * 0.025f, w * 0.81f, h * 0.22f);
        bRT.r.set(w * 0.822f, h * 0.005f, w * 0.955f, h * 0.24f);

        dpad.cx = w * 0.165f; dpad.cy = h * 0.44f; dpad.arm = u * 0.155f;

        float fx = w * 0.835f, fy = h * 0.44f, sp = u * 0.118f, br = u * 0.06f;
        faceCx = fx; faceCy = fy;
        circle(bY, fx, fy - sp, br);
        circle(bA, fx, fy + sp, br);
        circle(bX, fx - sp, fy, br);
        circle(bB, fx + sp, fy, br);
        for (Btn b : new Btn[]{bA, bB, bX, bY}) {
            b.shade = new RadialGradient(b.r.centerX() - br * 0.35f, b.r.centerY() - br * 0.45f, br * 1.5f,
                    new int[]{Color.rgb(70, 74, 84), Color.rgb(30, 32, 37), Color.rgb(14, 15, 18)},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        }

        left.cx = w * 0.355f; left.cy = h * 0.71f; left.range = u * 0.15f;
        right.cx = w * 0.645f; right.cy = h * 0.71f; right.range = u * 0.15f;

        circle(bView, w * 0.41f, h * 0.44f, u * 0.04f);
        circle(bMenuKey, w * 0.59f, h * 0.44f, u * 0.04f);
        circle(bHome, w * 0.5f, h * 0.575f, u * 0.055f);
        circle(bGear, w * 0.5f, h * 0.055f, u * 0.038f);
        circle(bLS, w * 0.095f, h * 0.81f, u * 0.048f);
        circle(bRS, w * 0.905f, h * 0.81f, u * 0.048f);

        ledX = w * 0.5f; ledY = h * 0.225f;

        if (body != null) body.recycle();
        body = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        renderBody(new Canvas(body), w, h);
    }

    private static void circle(Btn b, float cx, float cy, float r) { b.r.set(cx - r, cy - r, cx + r, cy + r); }

    private Path bodyPath(int w, int h) {
        Path b = new Path();
        b.moveTo(w * 0.22f, h * 0.14f);
        b.quadTo(w * 0.5f, h * 0.10f, w * 0.78f, h * 0.14f);
        b.cubicTo(w * 0.92f, h * 0.155f, w * 0.99f, h * 0.27f, w * 0.99f, h * 0.50f);
        b.cubicTo(w * 0.99f, h * 0.75f, w * 0.985f, h * 1.03f, w * 0.895f, h * 1.03f);
        b.cubicTo(w * 0.835f, h * 1.03f, w * 0.80f, h * 0.985f, w * 0.765f, h * 0.95f);
        b.cubicTo(w * 0.70f, h * 0.935f, w * 0.62f, h * 0.965f, w * 0.5f, h * 0.965f);
        b.cubicTo(w * 0.38f, h * 0.965f, w * 0.30f, h * 0.935f, w * 0.235f, h * 0.95f);
        b.cubicTo(w * 0.20f, h * 0.985f, w * 0.165f, h * 1.03f, w * 0.105f, h * 1.03f);
        b.cubicTo(w * 0.015f, h * 1.03f, w * 0.01f, h * 0.75f, w * 0.01f, h * 0.50f);
        b.cubicTo(w * 0.01f, h * 0.27f, w * 0.08f, h * 0.155f, w * 0.22f, h * 0.14f);
        b.close();
        return b;
    }

    /** Fond + coque laquée + cuvettes. Dessiné une seule fois. */
    private void renderBody(Canvas c, int w, int h) {
        // Fond (dessiné à chaque image, sous les gâchettes)
        bgShader = new RadialGradient(w * 0.5f, h * 0.45f, Math.max(w, h) * 0.7f,
                Color.rgb(22, 23, 28), Color.rgb(4, 4, 5), Shader.TileMode.CLAMP);

        Path shell = bodyPath(w, h);

        // Ombre portée de la coque
        p.setShader(null);
        p.setColor(Color.argb(200, 0, 0, 0));
        p.setShadowLayer(u * 0.05f, 0, u * 0.02f, Color.argb(220, 0, 0, 0));
        c.drawPath(shell, p);
        p.clearShadowLayer();

        // Coque noir laqué
        p.setShader(new LinearGradient(0, h * 0.1f, 0, h,
                new int[]{Color.rgb(46, 49, 57), Color.rgb(24, 26, 31), Color.rgb(11, 12, 15)},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        c.drawPath(shell, p);

        // Reflet brillant en haut
        c.save();
        c.clipPath(shell);
        p.setShader(new LinearGradient(0, h * 0.1f, 0, h * 0.45f,
                Color.argb(46, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP));
        c.drawOval(-w * 0.1f, -h * 0.25f, w * 1.1f, h * 0.48f, p);
        // Liseré lumineux sur l'arête supérieure
        p.setShader(null);
        s.setStyle(Paint.Style.STROKE);
        s.setStrokeWidth(u * 0.006f);
        s.setColor(Color.argb(55, 255, 255, 255));
        c.translate(0, u * 0.004f);
        c.drawPath(shell, s);
        c.restore();

        // Contour
        s.setColor(Color.BLACK);
        s.setStrokeWidth(u * 0.005f);
        c.drawPath(shell, s);

        // Cuvettes croix / touches
        well(c, dpad.cx, dpad.cy, u * 0.215f);
        well(c, bA.r.centerX(), bX.r.centerY(), u * 0.215f);
        // Cuvettes des sticks
        well(c, left.cx, left.cy, u * 0.155f);
        well(c, right.cx, right.cy, u * 0.155f);

        // Petits libellés sous View / Menu
        t.setTypeface(regular);
        t.setTextSize(u * 0.024f);
        t.setColor(LABEL_DIM);
        t.setLetterSpacing(0.15f);
        c.drawText("VIEW", bView.r.centerX(), bView.r.bottom + u * 0.04f, t);
        c.drawText("MENU", bMenuKey.r.centerX(), bMenuKey.r.bottom + u * 0.04f, t);

        // Numéros des voyants
        t.setTextSize(u * 0.02f);
        for (int i = 0; i < 4; i++) c.drawText(String.valueOf(i + 1), ledPos(i), ledY - u * 0.022f, t);

        // Signature
        t.setTypeface(bold);
        t.setTextSize(u * 0.032f);
        t.setLetterSpacing(0.35f);
        t.setColor(Color.rgb(52, 56, 66));
        c.drawText("SA CONTROLLER", w * 0.5f, h * 0.885f, t);
        t.setLetterSpacing(0f);
    }

    private void well(Canvas c, float cx, float cy, float r) {
        p.setShader(new LinearGradient(0, cy - r, 0, cy + r,
                Color.rgb(6, 7, 9), Color.rgb(34, 37, 44), Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, p);
        p.setShader(null);
        s.setStyle(Paint.Style.STROKE);
        s.setStrokeWidth(u * 0.004f);
        s.setShader(new LinearGradient(0, cy - r, 0, cy + r,
                Color.argb(200, 0, 0, 0), Color.argb(70, 255, 255, 255), Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, s);
        s.setShader(null);
    }

    private float ledPos(int i) { return ledX + (i - 1.5f) * u * 0.045f; }

    // ================= Dessin =================

    @Override protected void onDraw(Canvas c) {
        if (body == null) return;
        p.setShader(bgShader);
        c.drawRect(0, 0, getWidth(), getHeight(), p);
        p.setShader(null);
        // Les gâchettes passent sous la coque
        for (Btn b : shoulders) drawShoulder(c, b);
        c.drawBitmap(body, 0, 0, null);

        drawLeds(c);
        drawStatus(c);
        for (int i = controls.size() - 1; i >= 0; i--) {
            Control ctl = controls.get(i);
            if (ctl instanceof Btn && ((Btn) ctl).kind == SHOULDER) continue;
            ctl.draw(c);
        }
        if (DEBUG_HITS) drawHits(c);
        if (!connected) postInvalidateDelayed(500);
    }

    private void drawHits(Canvas c) {
        for (int y = 0; y < getHeight(); y += 6)
            for (int x = 0; x < getWidth(); x += 6) {
                for (int i = 0; i < controls.size(); i++) {
                    if (controls.get(i).hit(x, y)) {
                        p.setColor(Color.HSVToColor(new float[]{(i * 137) % 360, 1f, 1f}));
                        p.setAlpha(110);
                        c.drawRect(x, y, x + 6, y + 6, p);
                        break;
                    }
                }
            }
        p.setAlpha(255);
    }

    private final Path shoulderPath = new Path();

    private void drawShoulder(Canvas c, Btn b) {
        RectF r = b.r;
        // Coin extérieur des gâchettes très arrondi, pour épouser l'épaule de la coque
        float big = u * 0.1f, sm = u * 0.032f, bot = u * 0.02f;
        float tl = b == bLT ? big : sm, tr = b == bRT ? big : sm;
        shoulderPath.reset();
        shoulderPath.addRoundRect(r, new float[]{tl, tl, tr, tr, bot, bot, bot, bot}, Path.Direction.CW);
        float dy = b.pressed ? u * 0.014f : 0f;
        c.save();
        c.translate(0, dy);
        boolean trig = b == bLT || b == bRT;
        p.setShader(new LinearGradient(0, r.top, 0, r.top + u * 0.15f,
                b.pressed ? new int[]{Color.rgb(30, 34, 44), Color.rgb(16, 18, 23)}
                          : trig ? new int[]{Color.rgb(66, 70, 82), Color.rgb(22, 24, 29)}
                                 : new int[]{Color.rgb(84, 88, 100), Color.rgb(30, 32, 38)},
                null, Shader.TileMode.CLAMP));
        c.drawPath(shoulderPath, p);
        p.setShader(null);
        // Reflet sur l'arête haute
        s.setStyle(Paint.Style.STROKE);
        s.setStrokeWidth(u * 0.005f);
        s.setShader(new LinearGradient(0, r.top, 0, r.top + u * 0.06f,
                Color.argb(b.pressed ? 30 : 90, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP));
        c.save();
        c.translate(0, u * 0.003f);
        c.drawPath(shoulderPath, s);
        c.restore();
        s.setShader(null);
        s.setStrokeWidth(u * 0.004f);
        s.setColor(b.pressed ? GLOW : Color.BLACK);
        c.drawPath(shoulderPath, s);
        // Libellé dans la partie visible (au-dessus de la coque)
        t.setTypeface(bold);
        t.setTextSize(u * 0.042f);
        t.setColor(b.pressed ? GLOW : LABEL);
        if (b.pressed) t.setShadowLayer(u * 0.015f, 0, 0, GLOW);
        float ly = r.top + u * 0.065f;
        c.drawText(b.label, r.centerX(), ly - (t.ascent() + t.descent()) / 2f, t);
        t.clearShadowLayer();
        c.restore();
    }

    private void drawFace(Canvas c, Btn b) {
        float cx = b.r.centerX(), cy = b.r.centerY(), r = b.r.width() / 2f;
        float lift = u * 0.008f;
        if (b.pressed) {
            // Halo de couleur
            p.setShader(new RadialGradient(cx, cy, r * 1.9f,
                    Color.argb(150, Color.red(b.color), Color.green(b.color), Color.blue(b.color)),
                    Color.argb(0, Color.red(b.color), Color.green(b.color), Color.blue(b.color)),
                    Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r * 1.9f, p);
        } else {
            p.setShader(null);
            p.setColor(Color.argb(170, 0, 0, 0));
            c.drawCircle(cx, cy + lift, r * 1.02f, p);
        }
        float oy = b.pressed ? lift * 0.6f : 0f;
        c.save();
        c.translate(0, oy);
        if (b.pressed) c.scale(0.95f, 0.95f, cx, cy);
        p.setShader(b.shade);
        c.drawCircle(cx, cy, r, p);
        p.setShader(null);
        // Liseré
        s.setStyle(Paint.Style.STROKE);
        s.setStrokeWidth(u * 0.004f);
        s.setColor(b.pressed ? b.color : Color.rgb(8, 8, 10));
        c.drawCircle(cx, cy, r, s);
        // Reflet
        s.setColor(Color.argb(b.pressed ? 20 : 45, 255, 255, 255));
        s.setStrokeWidth(u * 0.005f);
        c.drawArc(cx - r * 0.8f, cy - r * 0.8f, cx + r * 0.8f, cy + r * 0.8f, 200, 140, false, s);
        // Lettre
        t.setTypeface(bold);
        t.setTextSize(r * 1.05f);
        t.setColor(b.pressed ? Color.WHITE : b.color);
        if (b.pressed) t.setShadowLayer(r * 0.35f, 0, 0, b.color);
        c.drawText(b.label, cx, cy - (t.ascent() + t.descent()) / 2f, t);
        t.clearShadowLayer();
        c.restore();
    }

    private void drawSmall(Canvas c, Btn b) {
        float cx = b.r.centerX(), cy = b.r.centerY(), r = b.r.width() / 2f;
        boolean on = b.pressed;
        p.setShader(null);
        p.setColor(Color.argb(160, 0, 0, 0));
        if (!on) c.drawCircle(cx, cy + u * 0.005f, r, p);
        p.setShader(new LinearGradient(0, cy - r, 0, cy + r,
                on ? new int[]{Color.rgb(24, 26, 32), Color.rgb(40, 44, 52)}
                   : new int[]{Color.rgb(64, 68, 78), Color.rgb(22, 24, 29)},
                null, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, p);
        p.setShader(null);
        s.setStyle(Paint.Style.STROKE);
        s.setStrokeWidth(u * 0.004f);

        int ink = on ? GLOW : LABEL;
        if ("home".equals(b.label)) {
            // Bouton central : anneau lumineux quand connecté
            int ring = connected || on ? GLOW : Color.rgb(70, 74, 84);
            s.setColor(ring);
            s.setStrokeWidth(u * 0.007f);
            if (connected || on) s.setShadowLayer(u * 0.02f, 0, 0, GLOW);
            c.drawCircle(cx, cy, r * 0.62f, s);
            s.clearShadowLayer();
            p.setColor(ring);
            c.drawCircle(cx, cy, r * 0.18f, p);
        } else if ("view".equals(b.label)) {
            s.setColor(ink);
            s.setStrokeWidth(u * 0.0045f);
            float q = r * 0.3f;
            c.drawRoundRect(cx - q * 1.2f, cy - q * 1.1f, cx + q * 0.5f, cy + q * 0.35f, q * 0.25f, q * 0.25f, s);
            c.drawRoundRect(cx - q * 0.5f, cy - q * 0.35f, cx + q * 1.2f, cy + q * 1.1f, q * 0.25f, q * 0.25f, s);
        } else if ("menu".equals(b.label)) {
            s.setColor(ink);
            s.setStrokeWidth(u * 0.0055f);
            s.setStrokeCap(Paint.Cap.ROUND);
            float q = r * 0.42f;
            for (int i = -1; i <= 1; i++) c.drawLine(cx - q, cy + i * q * 0.6f, cx + q, cy + i * q * 0.6f, s);
            s.setStrokeCap(Paint.Cap.BUTT);
        } else if ("gear".equals(b.label)) {
            s.setColor(ink);
            s.setStrokeWidth(u * 0.005f);
            c.drawCircle(cx, cy, r * 0.32f, s);
            s.setStrokeCap(Paint.Cap.ROUND);
            for (int i = 0; i < 8; i++) {
                double a = Math.PI / 4 * i;
                c.drawLine(cx + (float) Math.cos(a) * r * 0.45f, cy + (float) Math.sin(a) * r * 0.45f,
                        cx + (float) Math.cos(a) * r * 0.62f, cy + (float) Math.sin(a) * r * 0.62f, s);
            }
            s.setStrokeCap(Paint.Cap.BUTT);
        } else {
            t.setTypeface(bold);
            t.setTextSize(r * 0.72f);
            t.setColor(ink);
            c.drawText(b.label, cx, cy - (t.ascent() + t.descent()) / 2f, t);
        }
        s.setStrokeWidth(u * 0.004f);
        s.setColor(on ? GLOW : Color.BLACK);
        c.drawCircle(cx, cy, r, s);
    }

    private final Path dpadPiece = new Path();
    private final Path arrow = new Path();

    private void drawDPad(Canvas c, DPad d) {
        float hw = u * 0.047f, g = u * 0.022f, arm = d.arm;
        dpadPiece.reset();
        dpadPiece.moveTo(0, -g);
        dpadPiece.lineTo(hw, -g - hw);
        dpadPiece.lineTo(hw, -arm);
        dpadPiece.lineTo(-hw, -arm);
        dpadPiece.lineTo(-hw, -g - hw);
        dpadPiece.close();
        arrow.reset();
        float ay = -arm + u * 0.034f, aw = u * 0.017f;
        arrow.moveTo(0, ay - aw * 0.6f);
        arrow.lineTo(aw, ay + aw * 0.6f);
        arrow.lineTo(-aw, ay + aw * 0.6f);
        arrow.close();

        CornerPathEffect round = new CornerPathEffect(u * 0.012f);
        for (int i = 0; i < 4; i++) {
            boolean on = d.on(i * 2);
            c.save();
            c.translate(d.cx, d.cy);
            c.rotate(90 * i);
            // Ombre
            p.setPathEffect(round);
            p.setShader(null);
            if (!on) {
                p.setColor(Color.argb(170, 0, 0, 0));
                c.save();
                c.rotate(-90 * i);
                c.translate(0, u * 0.008f);
                c.rotate(90 * i);
                c.drawPath(dpadPiece, p);
                c.restore();
            }
            p.setShader(new LinearGradient(0, -arm, 0, -g,
                    on ? new int[]{Color.rgb(30, 34, 42), Color.rgb(22, 24, 30)}
                       : new int[]{Color.rgb(74, 78, 90), Color.rgb(28, 30, 36)},
                    null, Shader.TileMode.CLAMP));
            c.drawPath(dpadPiece, p);
            p.setShader(null);
            s.setPathEffect(round);
            s.setStyle(Paint.Style.STROKE);
            s.setStrokeWidth(u * 0.004f);
            s.setColor(on ? GLOW : Color.BLACK);
            c.drawPath(dpadPiece, s);
            s.setPathEffect(null);
            p.setPathEffect(null);
            p.setColor(on ? GLOW : Color.rgb(150, 156, 168));
            if (on) p.setShadowLayer(u * 0.015f, 0, 0, GLOW);
            c.drawPath(arrow, p);
            p.clearShadowLayer();
            c.restore();
        }
    }

    private void drawStick(Canvas c, Stick st) {
        float travel = u * 0.05f;
        float cx = st.cx + st.kx * travel, cy = st.cy + st.ky * travel;
        float r = u * 0.105f;
        // Ombre
        p.setShader(null);
        p.setColor(Color.argb(190, 0, 0, 0));
        c.drawCircle(cx, cy + u * 0.012f, r * 1.02f, p);
        if (st.active) {
            p.setShader(new RadialGradient(cx, cy, r * 1.6f,
                    Color.argb(110, 127, 184, 255), Color.argb(0, 127, 184, 255), Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r * 1.6f, p);
        }
        // Bord caoutchouc
        p.setShader(new LinearGradient(0, cy - r, 0, cy + r,
                Color.rgb(62, 66, 76), Color.rgb(16, 17, 21), Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, p);
        // Stries
        s.setShader(null);
        s.setStyle(Paint.Style.STROKE);
        s.setStrokeWidth(u * 0.004f);
        s.setColor(Color.argb(150, 0, 0, 0));
        for (int i = 0; i < 40; i++) {
            double a = Math.PI * 2 * i / 40;
            float cs = (float) Math.cos(a), sn = (float) Math.sin(a);
            c.drawLine(cx + cs * r * 0.8f, cy + sn * r * 0.8f, cx + cs * r * 0.97f, cy + sn * r * 0.97f, s);
        }
        // Creux central (concave : sombre en haut, clair en bas)
        float ir = r * 0.74f;
        p.setShader(new LinearGradient(0, cy - ir, 0, cy + ir,
                Color.rgb(12, 13, 16), Color.rgb(48, 52, 60), Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, ir, p);
        p.setShader(null);
        s.setColor(st.active ? GLOW : Color.rgb(8, 8, 10));
        s.setStrokeWidth(u * (st.active ? 0.006f : 0.004f));
        c.drawCircle(cx, cy, r, s);
        s.setColor(Color.argb(40, 255, 255, 255));
        s.setStrokeWidth(u * 0.003f);
        c.drawCircle(cx, cy, ir, s);
    }

    private void drawLeds(Canvas c) {
        boolean blinkOn = (SystemClock.uptimeMillis() / 500) % 2 == 0;
        float lw = u * 0.024f, lh = u * 0.011f;
        for (int i = 0; i < 4; i++) {
            boolean lit = connected ? i == 0 : blinkOn;
            float x = ledPos(i);
            p.setShader(null);
            p.setColor(lit ? LED_ON : LED_OFF);
            if (lit) p.setShadowLayer(u * 0.018f, 0, 0, LED_ON);
            c.drawRoundRect(x - lw / 2, ledY - lh / 2, x + lw / 2, ledY + lh / 2, lh / 2, lh / 2, p);
            p.clearShadowLayer();
        }
    }

    private void drawStatus(Canvas c) {
        if (status.isEmpty()) return;
        t.setTypeface(regular);
        t.setTextSize(u * 0.028f);
        t.setColor(connected ? Color.rgb(150, 190, 240) : Color.rgb(140, 146, 160));
        float max = getWidth() * 0.4f;
        CharSequence line = TextUtils.ellipsize(status, t, max, TextUtils.TruncateAt.END);
        c.drawText(line, 0, line.length(), getWidth() * 0.5f, ledY + u * 0.07f, t);
    }

    // ================= Toucher =================

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = e.getActionIndex();
                float x = e.getX(i), y = e.getY(i);
                for (Control ctl : controls) {
                    if (ctl.hit(x, y) && owners.indexOfValue(ctl) < 0) {
                        owners.put(e.getPointerId(i), ctl);
                        ctl.down(x, y);
                        break;
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    Control ctl = owners.get(e.getPointerId(i));
                    if (ctl != null) ctl.move(e.getX(i), e.getY(i));
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int id = e.getPointerId(e.getActionIndex());
                Control ctl = owners.get(id);
                if (ctl != null) { ctl.up(); owners.remove(id); }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                return true;
        }
        return super.onTouchEvent(e);
    }

    /** Relâche tout (appelé quand la vue disparaît). */
    public void releaseAll() {
        for (int i = 0; i < owners.size(); i++) owners.valueAt(i).up();
        owners.clear();
    }
}
