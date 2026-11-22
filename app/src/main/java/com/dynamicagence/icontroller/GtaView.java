package com.dynamicagence.icontroller;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.SparseArray;
import android.view.Display;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Écran dédié à GTA San Andreas : boutons nommés par action, modes « À pied » / « En voiture »,
 * zone caméra au centre (stick droit), stick gauche et conduite par inclinaison en option.
 * Même direction artistique que {@link GamepadView} ({@link Theme}).
 */
public class GtaView extends View implements SensorEventListener {

    public interface Listener {
        void onBack();
        void onSettings();
    }

    private static final int GLOW = Theme.BLUE;
    private static final int AMBER = Theme.AMBER;

    private final HidGamepad pad;
    private final GtaKeys keys;
    private Listener listener;
    private String status = "";
    private boolean connected;
    private int mode = GtaKeys.FOOT;
    public boolean debugHits; // dessine les zones tactiles (mise au point)

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint s = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint t = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private Bitmap body;   // décor pré-rendu (perf sur vieux téléphone)
    private float u, slop;

    private int accent() { return mode == GtaKeys.CAR ? AMBER : GLOW; }

    // ================= Contrôles =================

    private abstract class Control {
        final RectF r = new RectF();
        abstract boolean hit(float x, float y);
        abstract void down(float x, float y);
        void move(float x, float y) { }
        abstract void up();
        abstract void draw(Canvas c);
    }

    private static final int ROUND = 0, PILL = 1, PEDAL = 2;

    /** Bouton d'action GTA : envoie la touche de manette choisie dans les réglages. */
    private class ActionBtn extends Control {
        final GtaKeys.Action a; final int shape;
        boolean pressed; int held = GtaKeys.NONE; float glow;
        ActionBtn(String id, int shape) { this.a = GtaKeys.find(id); this.shape = shape; }
        boolean hit(float x, float y) {
            if (shape == ROUND)
                return Math.hypot(x - r.centerX(), y - r.centerY()) < r.width() / 2f + slop;
            return x >= r.left - slop && x <= r.right + slop && y >= r.top - slop && y <= r.bottom + slop;
        }
        void down(float x, float y) { set(true); }
        void up() { set(false); }
        void set(boolean on) {
            if (on == pressed) return;
            pressed = on;
            if (on) {
                held = keys.get(a);
                press(held, true);
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            } else {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_RELEASE);
                press(held, false);
                held = GtaKeys.NONE;
                if (a.toggleMode && keys.autoMode() && !releasing)
                    post(() -> setMode(mode == GtaKeys.FOOT ? GtaKeys.CAR : GtaKeys.FOOT));
            }
            invalidate();
        }
        void draw(Canvas c) { drawAction(c, this); }
    }

    /** Petit bouton rond de navigation (retour / réglages). */
    private class NavBtn extends Control {
        final boolean back; boolean pressed;
        NavBtn(boolean back) { this.back = back; }
        boolean hit(float x, float y) { return Math.hypot(x - r.centerX(), y - r.centerY()) < r.width() / 2f + slop * 1.5f; }
        void down(float x, float y) { pressed = true; performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); invalidate(); }
        void up() {
            if (!pressed) return;
            pressed = false;
            invalidate();
            post(() -> { if (listener != null) { if (back) listener.onBack(); else listener.onSettings(); } });
        }
        void draw(Canvas c) { drawNav(c, this); }
    }

    /** Interrupteur local (inclinaison, recentrage) : n'envoie rien à la TV. */
    private class OptBtn extends Control {
        final boolean tiltSwitch; boolean pressed;
        OptBtn(boolean tiltSwitch) { this.tiltSwitch = tiltSwitch; }
        boolean hit(float x, float y) {
            return x >= r.left - slop && x <= r.right + slop && y >= r.top - slop && y <= r.bottom + slop;
        }
        void down(float x, float y) {
            pressed = true;
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (tiltSwitch) { keys.setTilt(!keys.tilt()); updateSensor(); }
            else recenter();
            invalidate();
        }
        void up() { pressed = false; invalidate(); }
        void draw(Canvas c) { drawOpt(c, this); }
    }

    /** Grande bascule « À PIED | EN VOITURE ». */
    private class ModeSwitch extends Control {
        boolean hit(float x, float y) {
            return x >= r.left - slop && x <= r.right + slop && y >= r.top - slop && y <= r.bottom + slop;
        }
        void down(float x, float y) {
            int m = x < r.centerX() ? GtaKeys.FOOT : GtaKeys.CAR;
            if (m != mode) post(() -> setMode(m));
        }
        void up() { }
        void draw(Canvas c) { drawSwitch(c, this); }
    }

    private class Stick extends Control {
        float cx, cy, range, kx, ky; boolean active;
        boolean hit(float x, float y) { return Math.hypot(x - cx, y - cy) < range * 1.5f; }
        void down(float x, float y) { active = true; move(x, y); }
        void move(float x, float y) {
            float dx = (x - cx) / range, dy = (y - cy) / range;
            float m = (float) Math.hypot(dx, dy);
            if (m > 1f) { dx /= m; dy /= m; }
            if (m < 0.08f) { dx = 0; dy = 0; }
            kx = dx; ky = dy;
            pushLeft();
            invalidate();
        }
        void up() { active = false; kx = 0; ky = 0; pushLeft(); invalidate(); }
        void draw(Canvas c) { drawStick(c, this); }
    }

    /** Zone caméra : glisser depuis n'importe où = stick droit (origine au point d'appui). */
    private class CamPad extends Control {
        float ox, oy, kx, ky, fx, fy; boolean active;
        boolean hit(float x, float y) { return r.contains(x, y); }
        void down(float x, float y) { active = true; ox = x; oy = y; move(x, y); }
        void move(float x, float y) {
            fx = x; fy = y;
            float range = u * 0.13f;
            float dx = (x - ox) / range, dy = (y - oy) / range;
            float m = (float) Math.hypot(dx, dy);
            if (m > 1f) { dx /= m; dy /= m; }
            if (m < 0.06f) { dx = 0; dy = 0; }
            kx = dx; ky = dy;
            pad.setRightStick(dx, dy);
            invalidate();
        }
        void up() { active = false; kx = 0; ky = 0; pad.setRightStick(0, 0); invalidate(); }
        void draw(Canvas c) { drawCam(c, this); }
    }

    // Communs aux deux modes
    private final NavBtn back = new NavBtn(true);
    private final NavBtn gear = new NavBtn(false);
    private final ModeSwitch modeSwitch = new ModeSwitch();
    private final Stick stick = new Stick();
    private final CamPad cam = new CamPad();

    // À pied
    private final ActionBtn fTirer = new ActionBtn("pied.tirer", ROUND);
    private final ActionBtn fViser = new ActionBtn("pied.viser", ROUND);
    private final ActionBtn fSauter = new ActionBtn("pied.sauter", ROUND);
    private final ActionBtn fSprint = new ActionBtn("pied.sprint", ROUND);
    private final ActionBtn fEntrer = new ActionBtn("pied.entrer", ROUND);
    private final ActionBtn fAction = new ActionBtn("pied.action", PILL);
    private final ActionBtn fAccroupi = new ActionBtn("pied.accroupi", PILL);
    private final ActionBtn fArmeP = new ActionBtn("pied.arme_prec", PILL);
    private final ActionBtn fArmeS = new ActionBtn("pied.arme_suiv", PILL);
    private final ActionBtn fDerriere = new ActionBtn("pied.derriere", PILL);
    private final ActionBtn fNon = new ActionBtn("pied.non", PILL);
    private final ActionBtn fOui = new ActionBtn("pied.oui", PILL);
    private final ActionBtn fVue = new ActionBtn("pied.camera", PILL);
    private final ActionBtn fCarte = new ActionBtn("pied.carte", PILL);

    // En voiture
    private final ActionBtn cAccel = new ActionBtn("voiture.accel", PEDAL);
    private final ActionBtn cFrein = new ActionBtn("voiture.frein", PEDAL);
    private final ActionBtn cMain = new ActionBtn("voiture.main", PILL);
    private final ActionBtn cTirer = new ActionBtn("voiture.tirer", ROUND);
    private final ActionBtn cSortir = new ActionBtn("voiture.sortir", ROUND);
    private final ActionBtn cKlaxon = new ActionBtn("voiture.klaxon", PILL);
    private final ActionBtn cRadioP = new ActionBtn("voiture.radio_prec", PILL);
    private final ActionBtn cRadioS = new ActionBtn("voiture.radio_suiv", PILL);
    private final ActionBtn cMission = new ActionBtn("voiture.mission", PILL);
    private final ActionBtn cVue = new ActionBtn("voiture.camera", PILL);
    private final ActionBtn cCarte = new ActionBtn("voiture.carte", PILL);
    private final OptBtn tiltBtn = new OptBtn(true);
    private final OptBtn recenterBtn = new OptBtn(false);

    private final List<Control> controls = new ArrayList<>();
    private final SparseArray<Control> owners = new SparseArray<>();

    private float ledX, ledY;

    // ================= Touches envoyées (avec compteur, pour les touches partagées) =================

    private boolean releasing;
    private final int[] bitCount = new int[16];
    private final int[] hatCount = new int[4];

    private void press(int target, boolean on) {
        if (target == GtaKeys.NONE) return;
        if (target >= GtaKeys.HAT_UP) {
            int i = target - GtaKeys.HAT_UP;
            hatCount[i] = Math.max(0, hatCount[i] + (on ? 1 : -1));
            pad.setHat(hat());
        } else {
            boolean before = bitCount[target] > 0;
            bitCount[target] = Math.max(0, bitCount[target] + (on ? 1 : -1));
            boolean after = bitCount[target] > 0;
            if (before != after) pad.setButton(target, after);
        }
    }

    private int hat() {
        boolean up = hatCount[0] > 0, right = hatCount[1] > 0, down = hatCount[2] > 0, left = hatCount[3] > 0;
        if (up && down) { up = false; down = false; }
        if (left && right) { left = false; right = false; }
        if (up) return right ? 1 : left ? 7 : 0;
        if (down) return right ? 3 : left ? 5 : 4;
        if (right) return 2;
        if (left) return 6;
        return HidGamepad.HAT_CENTER;
    }

    // ================= Inclinaison =================

    private final SensorManager sensors;
    private final Sensor tiltSensor;
    private final boolean gravity;
    private boolean active, listening, lpInit;
    private final float[] lp = new float[3];
    private float tiltDeg, tiltZero, tiltX;
    private int tiltMax = 30;

    private boolean tiltOn() { return mode == GtaKeys.CAR && keys.tilt() && tiltSensor != null; }

    private void updateSensor() {
        boolean want = active && tiltOn();
        if (want && !listening) {
            lpInit = false;
            listening = sensors.registerListener(this, tiltSensor, SensorManager.SENSOR_DELAY_GAME);
        } else if (!want && listening) {
            sensors.unregisterListener(this);
            listening = false;
        }
        if (!listening) tiltX = 0;
        pushLeft();
        invalidate();
    }

    private void recenter() {
        tiltZero = tiltDeg;
        tiltX = 0;
        pushLeft();
    }

    @Override public void onSensorChanged(SensorEvent e) {
        float[] v = e.values;
        if (gravity || !lpInit) { lp[0] = v[0]; lp[1] = v[1]; lp[2] = v[2]; lpInit = true; }
        else for (int i = 0; i < 3; i++) lp[i] += (v[i] - lp[i]) * 0.25f; // filtre passe-bas
        // Vecteur « haut » ramené aux axes de l'écran (paysage dans un sens ou dans l'autre)
        Display d = getDisplay();
        int rot = d != null ? d.getRotation() : Surface.ROTATION_90;
        float sx, sy;
        switch (rot) {
            case Surface.ROTATION_90: sx = -lp[1]; sy = lp[0]; break;
            case Surface.ROTATION_270: sx = lp[1]; sy = -lp[0]; break;
            case Surface.ROTATION_180: sx = -lp[0]; sy = -lp[1]; break;
            default: sx = lp[0]; sy = lp[1];
        }
        // Rotation façon volant (bord droit qui descend = virage à droite)
        tiltDeg = (float) Math.toDegrees(Math.atan2(-sx, Math.hypot(sy, lp[2])));
        float a = tiltDeg - tiltZero, dead = 3f;
        float val = Math.abs(a) < dead ? 0f : Math.signum(a) * (Math.abs(a) - dead) / (tiltMax - dead);
        val = Math.max(-1f, Math.min(1f, val));
        if (Math.abs(val - tiltX) > 0.004f || (val == 0f && tiltX != 0f)) {
            tiltX = val;
            pushLeft();
            invalidate();
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    /** X du stick gauche : pouce + inclinaison (si active), jamais l'un sans l'autre. */
    private float leftX() {
        float x = listening ? tiltX + stick.kx : stick.kx;
        return Math.max(-1f, Math.min(1f, x));
    }

    /** Stick gauche = X (pouce + inclinaison) + Y du pouce. */
    private void pushLeft() {
        float x = leftX();
        pad.setLeftStick(x, stick.ky);
    }

    // ================= Cycle de vie =================

    public GtaView(Context ctx, HidGamepad pad, GtaKeys keys) {
        super(ctx);
        this.pad = pad;
        this.keys = keys;
        t.setTextAlign(Paint.Align.CENTER);
        sensors = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        Sensor g = sensors != null ? sensors.getDefaultSensor(Sensor.TYPE_GRAVITY) : null;
        gravity = g != null;
        tiltSensor = g != null ? g : sensors != null ? sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) : null;
        buildControls();
    }

    public void setListener(Listener l) { listener = l; }
    public void setStatus(String st) { status = st != null ? st : ""; invalidate(); }
    public void setConnected(boolean c) { connected = c; invalidate(); }
    public int getMode() { return mode; }

    /** Écran visible ou non : capteur branché seulement quand il sert. */
    public void setActive(boolean on) {
        active = on;
        if (!on) releaseAll();
        tiltMax = keys.tiltMax();
        updateSensor();
    }

    /** À appeler au retour des réglages (touches / options modifiées). */
    public void refreshKeys() {
        tiltMax = keys.tiltMax();
        updateSensor();
        invalidate();
    }

    public void setMode(int m) {
        if (m == mode) return;
        releaseAll();
        mode = m;
        buildControls();
        if (getWidth() > 0) layout(getWidth(), getHeight());
        updateSensor();
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        invalidate();
    }

    private void buildControls() {
        controls.clear();
        // Priorité au toucher : petits boutons d'abord, grandes zones à la fin.
        controls.add(back); controls.add(gear); controls.add(modeSwitch);
        if (mode == GtaKeys.FOOT) {
            controls.add(fVue); controls.add(fCarte);
            controls.add(fAccroupi); controls.add(fAction); controls.add(fArmeP); controls.add(fArmeS);
            controls.add(fNon); controls.add(fDerriere); controls.add(fOui);
            controls.add(fEntrer); controls.add(fSauter); controls.add(fViser); controls.add(fSprint); controls.add(fTirer);
        } else {
            controls.add(cVue); controls.add(cCarte);
            controls.add(tiltBtn); controls.add(recenterBtn);
            controls.add(cKlaxon); controls.add(cRadioP); controls.add(cRadioS); controls.add(cMission);
            controls.add(cMain); controls.add(cSortir); controls.add(cTirer); controls.add(cFrein); controls.add(cAccel);
        }
        controls.add(stick); controls.add(cam);
    }

    // ================= Mise en page =================

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (w == 0 || h == 0) return;
        layout(w, h);
    }

    private static void circle(Control b, float cx, float cy, float r) { b.r.set(cx - r, cy - r, cx + r, cy + r); }

    private void layout(int w, int h) {
        u = Math.min(h, w * 0.5f);
        slop = u * 0.015f;
        float m = u * 0.03f, L = m, R = w - m, B = h - m;

        // Barre du haut
        float ty = u * 0.085f, th = u * 0.045f;
        circle(back, L + u * 0.05f, ty, u * 0.045f);
        circle(gear, R - u * 0.05f, ty, u * 0.045f);
        modeSwitch.r.set(w * 0.5f - u * 0.33f, ty - u * 0.05f, w * 0.5f + u * 0.33f, ty + u * 0.05f);
        RectF vue = new RectF(L + u * 0.145f, ty - th, L + u * 0.385f, ty + th);
        RectF carte = new RectF(R - u * 0.385f, ty - th, R - u * 0.145f, ty + th);
        fVue.r.set(vue); cVue.r.set(vue);
        fCarte.r.set(carte); cCarte.r.set(carte);
        ledX = w * 0.5f; ledY = u * 0.172f;

        // Rangée de pilules sous la barre
        float py0 = u * 0.255f, py1 = u * 0.345f;

        // Stick gauche
        stick.cx = L + u * 0.27f; stick.cy = B - u * 0.25f; stick.range = u * 0.15f;
        float stickRight = stick.cx + stick.range * 1.5f;

        float camRight;
        float rowY0 = B - u * 0.10f, rowY1 = B - u * 0.01f;
        if (mode == GtaKeys.FOOT) {
            fAccroupi.r.set(L, py0, L + u * 0.22f, py1);
            fAction.r.set(L + u * 0.25f, py0, L + u * 0.47f, py1);
            fArmeP.r.set(R - u * 0.47f, py0, R - u * 0.25f, py1);
            fArmeS.r.set(R - u * 0.22f, py0, R, py1);

            circle(fTirer, R - u * 0.135f, B - u * 0.155f, u * 0.115f);
            circle(fViser, R - u * 0.135f, B - u * 0.43f, u * 0.10f);
            circle(fSprint, R - u * 0.39f, B - u * 0.115f, u * 0.10f);
            circle(fSauter, R - u * 0.375f, B - u * 0.355f, u * 0.09f);
            circle(fEntrer, R - u * 0.585f, B - u * 0.25f, u * 0.085f);
            camRight = fEntrer.r.left - slop * 2 - u * 0.01f;

            float mid = (stickRight + camRight) * 0.5f, dw = u * 0.22f, pw = u * 0.15f, g = u * 0.04f;
            fDerriere.r.set(mid - dw / 2, rowY0, mid + dw / 2, rowY1);
            fNon.r.set(mid - dw / 2 - g - pw, rowY0, mid - dw / 2 - g, rowY1);
            fOui.r.set(mid + dw / 2 + g, rowY0, mid + dw / 2 + g + pw, rowY1);
        } else {
            tiltBtn.r.set(L, py0, L + u * 0.24f, py1);
            recenterBtn.r.set(L + u * 0.28f, py0, L + u * 0.49f, py1);
            cKlaxon.r.set(R - u * 0.69f, py0, R - u * 0.49f, py1);
            cRadioP.r.set(R - u * 0.45f, py0, R - u * 0.24f, py1);
            cRadioS.r.set(R - u * 0.20f, py0, R, py1);

            cAccel.r.set(R - u * 0.23f, B - u * 0.56f, R, B);
            cFrein.r.set(R - u * 0.47f, B - u * 0.40f, R - u * 0.27f, B);
            cMain.r.set(R - u * 0.66f, B - u * 0.57f, R - u * 0.27f, B - u * 0.47f);
            circle(cTirer, R - u * 0.62f, B - u * 0.12f, u * 0.09f);
            circle(cSortir, R - u * 0.62f, B - u * 0.34f, u * 0.08f);
            camRight = Math.min(cTirer.r.left, cMain.r.left) - slop * 2 - u * 0.01f;

            float mid = (stickRight + camRight) * 0.5f, pw = u * 0.22f;
            cMission.r.set(mid - pw / 2, rowY0, mid + pw / 2, rowY1);
        }
        cam.r.set(stickRight + u * 0.02f, u * 0.235f, camRight, rowY0 - u * 0.025f);

        if (body != null) body.recycle();
        body = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        renderBody(new Canvas(body), w, h);
    }

    /** Fond + creux du stick et de la zone caméra (opaque). Redessiné seulement au changement de mode. */
    private void renderBody(Canvas c, int w, int h) {
        c.drawColor(Theme.BG);
        p.setShader(null);
        // Filet sous la barre du haut
        s.setStyle(Paint.Style.STROKE);
        s.setStrokeWidth(u * 0.003f);
        s.setColor(Theme.HAIR);
        float gy = u * 0.215f;
        c.drawLine(u * 0.03f, gy, w - u * 0.03f, gy, s);

        // Creux du stick
        well(c, stick.cx, stick.cy, stick.range * 1.08f);

        // Zone caméra
        RectF cr2 = cam.r;
        float rr = u * 0.05f;
        p.setColor(Theme.WELL);
        c.drawRoundRect(cr2, rr, rr, p);
        s.setStrokeWidth(u * 0.0035f);
        s.setColor(Theme.HAIR);
        c.drawRoundRect(cr2, rr, rr, s);
        p.setColor(Color.argb(22, 255, 255, 255));
        float step = u * 0.05f, dot = u * 0.003f;
        for (float y = cr2.top + step * 0.7f; y < cr2.bottom - step * 0.4f; y += step)
            for (float x = cr2.left + step * 0.7f; x < cr2.right - step * 0.4f; x += step)
                c.drawCircle(x, y, dot, p);
        t.setTypeface(Theme.MEDIUM);
        t.setTextSize(u * 0.03f);
        t.setLetterSpacing(0.3f);
        t.setColor(Theme.FAINT);
        c.drawText("CAMÉRA", cr2.centerX(), cr2.centerY() - u * 0.005f, t);
        t.setTypeface(Theme.REGULAR);
        t.setTextSize(u * 0.024f);
        t.setLetterSpacing(0.02f);
        c.drawText("glisse pour regarder autour", cr2.centerX(), cr2.centerY() + u * 0.04f, t);

        // Légende du stick
        t.setTypeface(Theme.MEDIUM);
        t.setTextSize(u * 0.022f);
        t.setLetterSpacing(0.14f);
        t.setColor(Theme.DIM);
        String leg = mode == GtaKeys.CAR ? "DIRECTION" : "MARCHER";
        c.drawText(leg, stick.cx, stick.cy - stick.range * 1.08f - u * 0.05f, t);
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
        if (debugHits) drawHits(c);
        boolean fading = false;
        for (Control ctl : controls)
            if (ctl instanceof ActionBtn && ((ActionBtn) ctl).glow > 0f && !((ActionBtn) ctl).pressed) { fading = true; break; }
        if (fading) postInvalidateOnAnimation();
        else if (!connected) postInvalidateDelayed(500);
    }

    private void drawHits(Canvas c) {
        for (int y = 0; y < getHeight(); y += 6)
            for (int x = 0; x < getWidth(); x += 6) {
                int n = 0, first = -1;
                for (int i = 0; i < controls.size(); i++)
                    if (controls.get(i).hit(x, y)) { if (first < 0) first = i; n++; }
                if (first < 0) continue;
                // Rouge vif = chevauchement de deux zones
                p.setShader(null);
                p.setColor(n > 1 ? Color.RED : Color.HSVToColor(new float[]{(first * 137) % 360, 0.9f, 1f}));
                p.setAlpha(n > 1 ? 255 : 100);
                c.drawRect(x, y, x + 6, y + 6, p);
            }
        p.setAlpha(255);
    }

    private static int mix(int a, int b, float k) {
        k = Math.max(0f, Math.min(1f, k));
        return Color.rgb((int) (Color.red(a) + (Color.red(b) - Color.red(a)) * k),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * k),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k));
    }

    private static float ease(ActionBtn b) {
        if (b.pressed) { b.glow = 1f; return 1f; }
        if (b.glow > 0f) b.glow = Math.max(0f, b.glow - 0.16f);
        return b.glow;
    }

    /** Coque de bouton (rond, pilule ou pédale) : aplat, filet fin ; enfoncé = teinte de l'accent. */
    private void drawCap(Canvas c, RectF r, int shape, float k, float rad) {
        int acc = accent();
        p.setShader(null);
        if (k > 0f) {
            p.setColor(Theme.alpha(acc, (int) (60 * k)));
            float g = u * 0.012f * k;
            rr.set(r.left - g, r.top - g, r.right + g, r.bottom + g);
            capShape(c, rr, shape, rad + g, p);
        }
        p.setColor(mix(Theme.SURFACE, mix(Theme.SURFACE, acc, 0.45f), k));
        capShape(c, r, shape, rad, p);
        s.setShader(null);
        s.setStyle(Paint.Style.STROKE);
        s.setStrokeWidth(u * (k > 0.5f ? 0.005f : 0.0035f));
        s.setColor(k > 0.5f ? acc : Theme.HAIR);
        capShape(c, r, shape, rad, s);
    }

    private final RectF rr = new RectF();

    private void capShape(Canvas c, RectF r, int shape, float rad, Paint paint) {
        if (shape == ROUND) c.drawCircle(r.centerX(), r.centerY(), r.width() / 2f, paint);
        else c.drawRoundRect(r, rad, rad, paint);
    }

    /** Taille de texte pour tenir dans une largeur. */
    private void fit(String txt, float size, float maxW) {
        t.setTextSize(size);
        float tw = t.measureText(txt);
        if (tw > maxW) t.setTextSize(size * maxW / tw);
    }

    private void drawAction(Canvas c, ActionBtn b) {
        RectF r = b.r;
        float k = ease(b);
        boolean on = b.pressed;
        int target = on ? b.held : keys.get(b.a);
        String key = GtaKeys.shortName(target);
        int keyCol = Theme.keyColor(target);
        float cx = r.centerX(), cy = r.centerY();
        int ink = mix(Theme.TEXT, Color.WHITE, k);

        if (b.shape == ROUND) {
            float rad = r.width() / 2f;
            c.save();
            c.scale(1f - 0.04f * k, 1f - 0.04f * k, cx, cy);
            drawCap(c, r, ROUND, k, 0);
            t.setTypeface(Theme.MEDIUM);
            t.setLetterSpacing(0.05f);
            fit(b.a.label, rad * 0.34f, rad * 1.5f);
            t.setColor(ink);
            c.drawText(b.a.label, cx, cy - rad * 0.06f, t);
            t.setLetterSpacing(0f);
            drawBadge(c, key, keyCol, cx, cy + rad * 0.42f, rad * 0.25f);
            c.restore();
        } else if (b.shape == PEDAL) {
            float rad = u * 0.045f;
            c.save();
            c.scale(1f - 0.02f * k, 1f - 0.02f * k, cx, cy);
            drawCap(c, r, PEDAL, k, rad);
            // Rainures
            s.setStyle(Paint.Style.STROKE);
            s.setStrokeCap(Paint.Cap.ROUND);
            s.setStrokeWidth(u * 0.006f);
            float x0 = r.left + r.width() * 0.26f, x1 = r.right - r.width() * 0.26f;
            float y0 = r.top + r.height() * 0.34f, y1 = r.bottom - r.height() * 0.28f;
            s.setColor(on ? Theme.alpha(accent(), 140) : Color.argb(26, 255, 255, 255));
            for (int i = 0; i < 4; i++) {
                float y = y0 + (y1 - y0) * i / 3f;
                c.drawLine(x0, y, x1, y, s);
            }
            s.setStrokeCap(Paint.Cap.BUTT);
            t.setTypeface(Theme.MEDIUM);
            t.setLetterSpacing(0.05f);
            fit(b.a.label, u * 0.034f, r.width() * 0.86f);
            t.setColor(ink);
            c.drawText(b.a.label, cx, r.top + r.height() * 0.17f - (t.ascent() + t.descent()) / 2f, t);
            t.setLetterSpacing(0f);
            drawBadge(c, key, keyCol, cx, r.bottom - r.height() * 0.10f, u * 0.028f);
            c.restore();
        } else {
            float rad = r.height() / 2f;
            drawCap(c, r, PILL, k, rad);
            float bs = r.height() * 0.28f, bw = badgeWidth(key, bs);
            float bx = r.right - rad * 0.3f - bw / 2f;
            float x0 = r.left + rad * 0.45f, x1 = bx - bw / 2f - rad * 0.2f;
            t.setTypeface(Theme.MEDIUM);
            fit(b.a.label, r.height() * 0.36f, x1 - x0);
            t.setColor(ink);
            c.drawText(b.a.label, (x0 + x1) / 2f, cy - (t.ascent() + t.descent()) / 2f, t);
            drawBadge(c, key, keyCol, bx, cy, bs);
        }
    }

    private float badgeWidth(String key, float size) {
        t.setTypeface(Theme.MEDIUM);
        t.setTextSize(size);
        return Math.max(t.measureText(key), size * 0.9f) + size * 0.8f;
    }

    /** Pastille de la touche de manette assignée (« RT », « A »…). */
    private void drawBadge(Canvas c, String key, int col, float cx, float cy, float size) {
        t.setTypeface(Theme.MEDIUM);
        t.setTextSize(size);
        float tw = Math.max(t.measureText(key), size * 0.9f);
        float bh = size * 1.45f, bw = tw + size * 0.8f;
        p.setShader(null);
        p.setColor(Color.argb(150, 0, 0, 0));
        c.drawRoundRect(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2, bh / 2, bh / 2, p);
        t.setColor(col);
        c.drawText(key, cx, cy - (t.ascent() + t.descent()) / 2f, t);
    }

    private void drawOpt(Canvas c, OptBtn b) {
        RectF r = b.r;
        boolean lit = b.tiltSwitch ? keys.tilt() : b.pressed;
        boolean disabled = !b.tiltSwitch && !listening;
        drawCap(c, r, PILL, b.pressed ? 1f : 0f, r.height() / 2f);
        float cy = r.centerY();
        String txt = b.tiltSwitch ? "Inclinaison" : "Recentrer";
        t.setTypeface(Theme.MEDIUM);
        if (b.tiltSwitch) {
            float lx = r.left + r.height() * 0.45f, lr = r.height() * 0.12f;
            p.setShader(null);
            p.setColor(lit ? Theme.AMBER : Theme.FAINT);
            c.drawCircle(lx, cy, lr, p);
            fit(txt, r.height() * 0.36f, r.width() - r.height() * 1.1f);
            t.setColor(lit ? Theme.TEXT : Theme.DIM);
            c.drawText(txt, (lx + lr + r.right) / 2f, cy - (t.ascent() + t.descent()) / 2f, t);
        } else {
            fit(txt, r.height() * 0.36f, r.width() * 0.8f);
            t.setColor(disabled ? Theme.FAINT : b.pressed ? Color.WHITE : Theme.TEXT);
            c.drawText(txt, r.centerX(), cy - (t.ascent() + t.descent()) / 2f, t);
        }
    }

    /** Contrôle segmenté « À PIED | EN VOITURE ». */
    private void drawSwitch(Canvas c, ModeSwitch m) {
        RectF r = m.r;
        float rad = r.height() / 2f;
        p.setShader(null);
        p.setColor(Theme.SURFACE);
        c.drawRoundRect(r, rad, rad, p);
        s.setShader(null);
        s.setStyle(Paint.Style.STROKE);
        s.setStrokeWidth(u * 0.0035f);
        s.setColor(Theme.HAIR);
        c.drawRoundRect(r, rad, rad, s);
        float in = u * 0.008f;
        boolean car = mode == GtaKeys.CAR;
        rr.set(car ? r.centerX() : r.left + in, r.top + in, car ? r.right - in : r.centerX(), r.bottom - in);
        float kr = rr.height() / 2f;
        p.setColor(accent());
        c.drawRoundRect(rr, kr, kr, p);
        t.setTypeface(Theme.MEDIUM);
        t.setLetterSpacing(0.1f);
        float half = r.width() / 2f;
        fit("EN VOITURE", r.height() * 0.34f, half * 0.78f);
        float ty = r.centerY() - (t.ascent() + t.descent()) / 2f;
        t.setColor(car ? Theme.DIM : Color.rgb(8, 8, 10));
        c.drawText("À PIED", r.left + half / 2f, ty, t);
        t.setColor(car ? Color.rgb(8, 8, 10) : Theme.DIM);
        c.drawText("EN VOITURE", r.centerX() + half / 2f, ty, t);
        t.setLetterSpacing(0f);
    }

    private void drawNav(Canvas c, NavBtn b) {
        RectF r = b.r;
        float cx = r.centerX(), cy = r.centerY(), rad = r.width() / 2f;
        drawCap(c, r, ROUND, b.pressed ? 1f : 0f, 0);
        int ink = b.pressed ? Color.WHITE : Theme.TEXT;
        s.setStyle(Paint.Style.STROKE);
        s.setColor(ink);
        s.setStrokeCap(Paint.Cap.ROUND);
        s.setStrokeJoin(Paint.Join.ROUND);
        if (b.back) {
            s.setStrokeWidth(u * 0.007f);
            float q = rad * 0.32f;
            Path ch = new Path();
            ch.moveTo(cx + q * 0.4f, cy - q);
            ch.lineTo(cx - q * 0.6f, cy);
            ch.lineTo(cx + q * 0.4f, cy + q);
            c.drawPath(ch, s);
        } else {
            // Touches : réglages à curseurs
            s.setStrokeWidth(u * 0.0055f);
            float q = rad * 0.5f;
            c.drawLine(cx - q, cy - q * 0.55f, cx + q, cy - q * 0.55f, s);
            c.drawLine(cx - q, cy + q * 0.55f, cx + q, cy + q * 0.55f, s);
            p.setShader(null);
            p.setColor(ink);
            c.drawCircle(cx + q * 0.3f, cy - q * 0.55f, rad * 0.14f, p);
            c.drawCircle(cx - q * 0.3f, cy + q * 0.55f, rad * 0.14f, p);
        }
        s.setStrokeCap(Paint.Cap.BUTT);
        s.setStrokeJoin(Paint.Join.MITER);
        t.setTypeface(Theme.MEDIUM);
        t.setTextSize(u * 0.02f);
        t.setLetterSpacing(0.12f);
        t.setColor(Theme.DIM);
        c.drawText(b.back ? "CONNEXION" : "TOUCHES", cx, r.bottom + u * 0.032f, t);
        t.setLetterSpacing(0f);
    }

    private void drawStick(Canvas c, Stick st) {
        float travel = u * 0.05f;
        float kx = leftX();
        float cx = st.cx + kx * travel, cy = st.cy + st.ky * travel;
        float r = u * 0.105f;
        int acc = accent();
        boolean lit = st.active || (listening && tiltX != 0f);

        if (listening) {
            // Jauge d'inclinaison en arc au-dessus du stick
            float ar = st.range * 1.22f;
            RectF ov = new RectF(st.cx - ar, st.cy - ar, st.cx + ar, st.cy + ar);
            s.setStyle(Paint.Style.STROKE);
            s.setStrokeCap(Paint.Cap.ROUND);
            s.setStrokeWidth(u * 0.01f);
            s.setColor(Theme.alpha(Color.WHITE, 30));
            c.drawArc(ov, 230, 80, false, s);
            s.setColor(acc);
            float sweep = tiltX * 40f;
            if (Math.abs(sweep) < 1.5f) c.drawArc(ov, 269, 2, false, s);
            else c.drawArc(ov, 270, sweep, false, s);
            s.setStrokeCap(Paint.Cap.BUTT);
        }

        p.setShader(null);
        if (lit) {
            p.setColor(Theme.alpha(acc, 60));
            c.drawCircle(cx, cy, r * 1.35f, p);
        }
        p.setColor(Theme.SURFACE_HI);
        c.drawCircle(cx, cy, r, p);
        s.setShader(null);
        s.setStyle(Paint.Style.STROKE);
        s.setStrokeWidth(u * (lit ? 0.006f : 0.0035f));
        s.setColor(lit ? acc : Theme.HAIR);
        c.drawCircle(cx, cy, r, s);
        p.setColor(lit ? Theme.alpha(acc, 70) : Theme.SURFACE);
        c.drawCircle(cx, cy, r * 0.58f, p);
        s.setStrokeWidth(u * 0.003f);
        s.setColor(Theme.HAIR);
        c.drawCircle(cx, cy, r * 0.58f, s);
        if (listening) {
            t.setTypeface(Theme.MEDIUM);
            t.setTextSize(u * 0.019f);
            t.setLetterSpacing(0.1f);
            t.setColor(acc);
            c.drawText("INCLINAISON", cx, cy - (t.ascent() + t.descent()) / 2f, t);
            t.setLetterSpacing(0f);
        }
    }

    private void drawCam(Canvas c, CamPad cp) {
        if (!cp.active) return;
        int acc = accent();
        float range = u * 0.13f;
        float hx = cp.ox + cp.kx * range, hy = cp.oy + cp.ky * range;
        c.save();
        c.clipRect(cp.r);
        s.setStyle(Paint.Style.STROKE);
        s.setStrokeWidth(u * 0.004f);
        s.setColor(Theme.alpha(acc, 110));
        c.drawCircle(cp.ox, cp.oy, range, s);
        s.setStrokeWidth(u * 0.005f);
        s.setColor(acc);
        c.drawLine(cp.ox, cp.oy, hx, hy, s);
        p.setShader(null);
        p.setColor(Theme.alpha(acc, 70));
        c.drawCircle(hx, hy, u * 0.05f, p);
        p.setColor(acc);
        c.drawCircle(hx, hy, u * 0.018f, p);
        c.restore();
    }

    private void drawStatus(Canvas c) {
        boolean beat = (SystemClock.uptimeMillis() / 500) % 2 == 0;
        Theme.statusPill(c, p, s, t, status, connected, beat, getWidth() * 0.5f, ledY, u * 0.025f, u * 0.8f);
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
                if (ctl != null) { owners.remove(id); ctl.up(); }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                return true;
        }
        return super.onTouchEvent(e);
    }

    /** Relâche tout : doigts, touches, croix, sticks (changement d'écran ou de mode). */
    public void releaseAll() {
        List<Control> held = new ArrayList<>();
        for (int i = 0; i < owners.size(); i++) held.add(owners.valueAt(i));
        owners.clear();
        releasing = true;
        for (Control ctl : held) {
            if (ctl instanceof NavBtn) ((NavBtn) ctl).pressed = false; // pas de navigation fantôme
            ctl.up();
        }
        releasing = false;
        // Filet de sécurité : rien ne doit rester enfoncé côté TV
        for (int b = 0; b < bitCount.length; b++)
            if (bitCount[b] > 0) { bitCount[b] = 0; pad.setButton(b, false); }
        java.util.Arrays.fill(hatCount, 0);
        pad.setHat(HidGamepad.HAT_CENTER);
        stick.kx = 0; stick.ky = 0; stick.active = false;
        cam.kx = 0; cam.ky = 0; cam.active = false;
        pad.setRightStick(0, 0);
        pushLeft();
        invalidate();
    }
}
