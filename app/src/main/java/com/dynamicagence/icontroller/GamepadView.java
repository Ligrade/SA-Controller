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
 * Manette tactile : disposition à deux sticks symétriques, croix en 4 flèches, touches aux
 * couleurs Xbox. Surfaces mates sur fond noir, filets fins, un seul accent bleu.
 */
public class GamepadView extends View {

    public interface MenuListener { void onMenu(); }

    private static final int A_COL = Theme.KA;
    private static final int B_COL = Theme.KB;
    private static final int X_COL = Theme.KX;
    private static final int Y_COL = Theme.KY;

    private final HidGamepad pad;
    private MenuListener menuListener;
    private String status = "";
    private boolean connected;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint s = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint t = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private static final boolean DEBUG_HITS = false; // dessine les zones tactiles (mise au point)

    private Bitmap body;   // coque pré-rendue, transparente autour (perf sur vieux téléphone)
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
        float glow;
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
            performHapticFeedback(on ? HapticFeedbackConstants.KEYBOARD_TAP : HapticFeedbackConstants.KEYBOARD_RELEASE);
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

        left.cx = w * 0.355f; left.cy = h * 0.71f; left.range = u * 0.15f;
        right.cx = w * 0.645f; right.cy = h * 0.71f; right.range = u * 0.15f;

        circle(bView, w * 0.41f, h * 0.44f, u * 0.04f);
        circle(bMenuKey, w * 0.59f, h * 0.44f, u * 0.04f);
        circle(bHome, w * 0.5f, h * 0.575f, u * 0.055f);
        circle(bGear, w * 0.5f, h * 0.055f, u * 0.038f);
        circle(bLS, w * 0.095f, h * 0.81f, u * 0.048f);
        circle(bRS, w * 0.905f, h * 0.81f, u * 0.048f);

        ledX = w * 0.5f; ledY = h * 0.2f;

        if (body != null) body.recycle();
        body = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        renderBody(new Canvas(body), w, h);
    }

    private static void circle(Btn b, float cx, float cy, float r) { b.r.set(cx - r, cy - r, cx + r, cy + r); }

    /** Fond + cuvettes + libellés fixes. Dessiné une seule fois (opaque). */
    private void renderBody(Canvas c, int w, int h) {
        c.drawColor(Theme.BG);
        // Cuvettes : croix, touches, sticks
        well(c, dpad.cx, dpad.cy, u * 0.215f);
        well(c, bA.r.centerX(), bX.r.centerY(), u * 0.215f);
        well(c, left.cx, left.cy, u * 0.155f);
        well(c, right.cx, right.cy, u * 0.155f);

        t.setTypeface(Theme.MEDIUM);
        t.setTextSize(u * 0.023f);
        t.setLetterSpacing(0.14f);
        t.setColor(Theme.DIM);
        c.drawText("VIEW", bView.r.centerX(), bView.r.bottom + u * 0.04f, t);
        c.drawText("MENU", bMenuKey.r.centerX(), bMenuKey.r.bottom + u * 0.04f, t);

        // Signature
        t.setTypeface(Theme.LIGHT);
        t.setTextSize(u * 0.03f);
        t.setLetterSpacing(0.42f);
        t.setColor(Theme.FAINT);
        c.drawText("SA CONTROLLER", w * 0.5f, h * 0.93f, t);
        t.setLetterSpacing(0f);
    }

    private void well(Canvas c, float cx, float cy, float r) {
        p.setShader(null);
        p.setColor(Theme.WELL);
        c.drawCircle(cx, cy, r, p);
        s.setShader(null);
        s.setStyle(Paint.Style.STROKE);
        s.setStrokeWidth(u * 0.0035f);
        s.setColor(Theme.HAIR);
        c.drawCircle(cx, cy, r, s);
    }

    // ================= Dessin =================

    @Override protected void onDraw(Canvas c) {
        if (body == null) return;
        c.drawBitmap(body, 0, 0, null);
        drawStatus(c);
        for (int i = controls.size() - 1; i >= 0; i--) controls.get(i).draw(c);
        if (DEBUG_HITS) drawHits(c);
        boolean fading = false;
        for (Control ctl : controls)
            if (ctl instanceof Btn && ((Btn) ctl).glow > 0f && !((Btn) ctl).pressed) { fading = true; break; }
        if (fading) postInvalidateOnAnimation();
        else if (!connected) postInvalidateDelayed(500);
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

    /** Valeur d'animation : plein dès l'appui, retombe en quelques images au relâchement. */
    private static float ease(Btn b) {
        if (b.pressed) { b.glow = 1f; return 1f; }
        if (b.glow > 0f) b.glow = Math.max(0f, b.glow - 0.16f);
        return b.glow;
    }

    private final RectF rr = new RectF();
    private final Path shoulderPath = new Path();

    private void drawShoulder(Canvas c, Btn b) {
        float k = ease(b);
        RectF r = b.r;
        float vis = u * 0.17f, big = u * 0.1f, sm = u * 0.03f, bot = u * 0.03f;
        float tl = b == bLT ? big : sm, tr = b == bRT ? big : sm;
        rr.set(r.left, r.top + u * 0.012f, r.right, r.top + vis);
        shoulderPath.reset();
        shoulderPath.addRoundRect(rr, new float[]{tl, tl, tr, tr, bot, bot, bot, bot}, Path.Direction.CW);
        p.setShader(null);
        p.setColor(mix(Theme.SURFACE, Theme.alpha(Theme.BLUE, 255), k * 0.45f));
        c.drawPath(shoulderPath, p);
        s.setStyle(Paint.Style.STROKE);
        s.setStrokeWidth(u * (k > 0.5f ? 0.005f : 0.0035f));
        s.setColor(k > 0.5f ? Theme.BLUE : Theme.HAIR);
        c.drawPath(shoulderPath, s);
        t.setTypeface(Theme.MEDIUM);
        t.setTextSize(u * 0.04f);
        t.setLetterSpacing(0.06f);
        t.setColor(mix(Theme.DIM, Color.WHITE, k));
        c.drawText(b.label, rr.centerX(), rr.centerY() - (t.ascent() + t.descent()) / 2f, t);
        t.setLetterSpacing(0f);
    }

    private static int mix(int a, int b, float k) {
        k = Math.max(0f, Math.min(1f, k));
        return Color.rgb((int) (Color.red(a) + (Color.red(b) - Color.red(a)) * k),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * k),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k));
    }

    private void drawFace(Canvas c, Btn b) {
        float k = ease(b);
        float cx = b.r.centerX(), cy = b.r.centerY(), r = b.r.width() / 2f;
        if (k > 0f) { // halo doux
            p.setShader(null);
            p.setColor(Theme.alpha(b.color, (int) (70 * k)));
            c.drawCircle(cx, cy, r * (1.18f + 0.1f * k), p);
        }
        c.save();
        c.scale(1f - 0.05f * k, 1f - 0.05f * k, cx, cy);
        p.setShader(null);
        p.setColor(mix(Theme.SURFACE, b.color, k));
        c.drawCircle(cx, cy, r, p);
        s.setStyle(Paint.Style.STROKE);
        s.setStrokeWidth(u * 0.0035f);
        s.setColor(k > 0.4f ? b.color : Theme.HAIR);
        c.drawCircle(cx, cy, r, s);
        t.setTypeface(Theme.MEDIUM);
        t.setTextSize(r * 1.0f);
        t.setColor(mix(b.color, Theme.BG, k));
        c.drawText(b.label, cx, cy - (t.ascent() + t.descent()) / 2f, t);
        c.restore();
    }

    private void drawSmall(Canvas c, Btn b) {
        float k = ease(b);
        float cx = b.r.centerX(), cy = b.r.centerY(), r = b.r.width() / 2f;
        boolean home = "home".equals(b.label);
        p.setShader(null);
        p.setColor(mix(Theme.SURFACE, Theme.SURFACE_HI, k));
        c.drawCircle(cx, cy, r, p);
        s.setStyle(Paint.Style.STROKE);
        s.setStrokeWidth(u * 0.0035f);
        s.setColor(k > 0.4f ? Theme.BLUE : Theme.HAIR);
        c.drawCircle(cx, cy, r, s);

        int ink = mix(Theme.DIM, Theme.BLUE, k);
        s.setStrokeCap(Paint.Cap.ROUND);
        s.setStrokeJoin(Paint.Join.ROUND);
        if (home) {
            int ring = connected ? Theme.GREEN : mix(Theme.FAINT, Theme.BLUE, k);
            s.setColor(ring);
            s.setStrokeWidth(u * 0.006f);
            c.drawCircle(cx, cy, r * 0.56f, s);
            p.setColor(ring);
            c.drawCircle(cx, cy, r * 0.17f, p);
        } else if ("view".equals(b.label)) {
            s.setColor(ink);
            s.setStrokeWidth(u * 0.0042f);
            float q = r * 0.3f;
            c.drawRoundRect(cx - q * 1.2f, cy - q * 1.1f, cx + q * 0.5f, cy + q * 0.35f, q * 0.25f, q * 0.25f, s);
            c.drawRoundRect(cx - q * 0.5f, cy - q * 0.35f, cx + q * 1.2f, cy + q * 1.1f, q * 0.25f, q * 0.25f, s);
        } else if ("menu".equals(b.label)) {
            s.setColor(ink);
            s.setStrokeWidth(u * 0.0052f);
            float q = r * 0.42f;
            for (int i = -1; i <= 1; i++) c.drawLine(cx - q, cy + i * q * 0.6f, cx + q, cy + i * q * 0.6f, s);
        } else if ("gear".equals(b.label)) {
            s.setColor(ink);
            // Retour à la connexion : trois traits « réglages »
            s.setStrokeWidth(u * 0.0048f);
            float q = r * 0.46f;
            c.drawLine(cx - q, cy - q * 0.55f, cx + q, cy - q * 0.55f, s);
            c.drawLine(cx - q, cy + q * 0.55f, cx + q, cy + q * 0.55f, s);
            p.setColor(ink);
            c.drawCircle(cx + q * 0.3f, cy - q * 0.55f, r * 0.14f, p);
            c.drawCircle(cx - q * 0.3f, cy + q * 0.55f, r * 0.14f, p);
        } else {
            t.setTypeface(Theme.MEDIUM);
            t.setTextSize(r * 0.68f);
            t.setColor(ink);
            c.drawText(b.label, cx, cy - (t.ascent() + t.descent()) / 2f, t);
        }
        s.setStrokeCap(Paint.Cap.BUTT);
        s.setStrokeJoin(Paint.Join.MITER);
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

        CornerPathEffect round = new CornerPathEffect(u * 0.014f);
        for (int i = 0; i < 4; i++) {
            boolean on = d.on(i * 2);
            c.save();
            c.translate(d.cx, d.cy);
            c.rotate(90 * i);
            p.setPathEffect(round);
            p.setShader(null);
            p.setColor(on ? Theme.alpha(Theme.BLUE, 255) : Theme.SURFACE);
            if (on) p.setColor(mix(Theme.SURFACE, Theme.BLUE, 0.5f));
            c.drawPath(dpadPiece, p);
            s.setPathEffect(round);
            s.setStyle(Paint.Style.STROKE);
            s.setStrokeWidth(u * (on ? 0.005f : 0.0035f));
            s.setColor(on ? Theme.BLUE : Theme.HAIR);
            c.drawPath(dpadPiece, s);
            s.setPathEffect(null);
            p.setPathEffect(null);
            p.setColor(on ? Color.WHITE : Theme.DIM);
            c.drawPath(arrow, p);
            c.restore();
        }
    }

    private void drawStick(Canvas c, Stick st) {
        float travel = u * 0.05f;
        float cx = st.cx + st.kx * travel, cy = st.cy + st.ky * travel;
        float r = u * 0.105f;
        p.setShader(null);
        if (st.active) {
            p.setColor(Theme.alpha(Theme.BLUE, 60));
            c.drawCircle(cx, cy, r * 1.35f, p);
        }
        p.setColor(Theme.SURFACE_HI);
        c.drawCircle(cx, cy, r, p);
        s.setShader(null);
        s.setStyle(Paint.Style.STROKE);
        s.setStrokeWidth(u * (st.active ? 0.006f : 0.0035f));
        s.setColor(st.active ? Theme.BLUE : Theme.HAIR);
        c.drawCircle(cx, cy, r, s);
        // Disque intérieur
        p.setColor(st.active ? Theme.alpha(Theme.BLUE, 70) : Theme.SURFACE);
        c.drawCircle(cx, cy, r * 0.58f, p);
        s.setStrokeWidth(u * 0.003f);
        s.setColor(Theme.HAIR);
        c.drawCircle(cx, cy, r * 0.58f, s);
    }

    private void drawStatus(Canvas c) {
        boolean beat = (SystemClock.uptimeMillis() / 500) % 2 == 0;
        Theme.statusPill(c, p, s, t, status, connected, beat, getWidth() * 0.5f, ledY, u * 0.027f, getWidth() * 0.3f);
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
