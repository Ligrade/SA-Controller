package com.dynamicagence.icontroller;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Écran « Touches GTA » : pour chaque action, la touche de manette envoyée à la TV.
 * Touche une ligne → liste de choix. Tout est sauvegardé tout de suite.
 */
public class GtaSettingsView extends ScrollView {

    public interface Listener { void onClose(); }

    private final GtaKeys keys;
    private final Listener listener;
    private final LinearLayout col;

    public GtaSettingsView(Context ctx, GtaKeys keys, Listener listener) {
        super(ctx);
        this.keys = keys;
        this.listener = listener;
        setBackgroundColor(Theme.BG);
        setFillViewport(true);
        setVerticalScrollBarEnabled(false);
        col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(28), dp(16), dp(28), dp(28));
        addView(col);
        build();
    }

    private void build() {
        col.removeAllViews();

        // En-tête : retour + titre
        LinearLayout head = new LinearLayout(getContext());
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        FrameBack back = new FrameBack();
        head.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout tt = new LinearLayout(getContext());
        tt.setOrientation(LinearLayout.VERTICAL);
        tt.setPadding(dp(14), 0, 0, 0);
        tt.addView(text("Touches", 24, Theme.LIGHT, Theme.TEXT));
        tt.addView(text("GTA San Andreas", 13, Theme.REGULAR, Theme.DIM));
        head.addView(tt);
        col.addView(head);

        TextView note = text("Les touches par défaut sont une supposition, à confirmer en jeu. "
                + "Si une action ne réagit pas, choisis une autre touche (ou regarde les commandes manette "
                + "dans les réglages du jeu). Tout est enregistré tout de suite.", 13, Theme.REGULAR, Theme.DIM);
        note.setLineSpacing(0, 1.2f);
        note.setPadding(dp(2), dp(14), 0, dp(6));
        col.addView(note);

        // Deux colonnes côte à côte (paysage)
        LinearLayout cols = new LinearLayout(getContext());
        cols.setOrientation(LinearLayout.HORIZONTAL);
        cols.addView(column("À PIED", Theme.BLUE, GtaKeys.FOOT), weighted(dp(14)));
        cols.addView(column("EN VOITURE", Theme.AMBER, GtaKeys.CAR), weighted(0));
        col.addView(cols);

        // Options
        col.addView(section("OPTIONS", Theme.DIM));
        col.addView(toggleRow("Changer de mode avec Entrer / Sortir", keys.autoMode(), v -> {
            keys.setAutoMode(!keys.autoMode());
            build();
        }));
        col.addView(row("Inclinaison : angle pour braquer à fond",
                tiltLabel(keys.tiltMax()), Theme.TEXT, v -> chooseTilt()));
        TextView reset = text("Remettre les touches par défaut", 14, Theme.MEDIUM, Theme.RED);
        reset.setGravity(Gravity.CENTER);
        reset.setMinHeight(dp(46));
        reset.setPadding(dp(18), dp(10), dp(18), dp(10));
        reset.setBackground(Theme.surface(Theme.SURFACE, Theme.HAIR, dp(23), 1));
        reset.setOnClickListener(v -> new AlertDialog.Builder(getContext(), AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Remettre les touches par défaut ?")
                .setMessage("Tes réglages de touches GTA seront effacés.")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Remettre", (d, w) -> { keys.resetKeys(); build(); })
                .show());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        col.addView(reset, lp);
    }

    /** Bouton retour : cercle plat + chevron. */
    private final class FrameBack extends View {
        private final android.graphics.Paint s = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        FrameBack() {
            super(GtaSettingsView.this.getContext());
            setBackground(Theme.surface(Theme.SURFACE, Theme.HAIR, dp(22), 1));
            setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); listener.onClose(); });
            s.setStyle(android.graphics.Paint.Style.STROKE);
            s.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            s.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            s.setColor(Theme.TEXT);
            s.setStrokeWidth(dp(2));
        }
        @Override protected void onDraw(android.graphics.Canvas c) {
            super.onDraw(c);
            float cx = getWidth() / 2f, cy = getHeight() / 2f, q = dp(6);
            android.graphics.Path p = new android.graphics.Path();
            p.moveTo(cx + q * 0.4f, cy - q);
            p.lineTo(cx - q * 0.6f, cy);
            p.lineTo(cx + q * 0.4f, cy + q);
            c.drawPath(p, s);
        }
    }

    private LinearLayout.LayoutParams weighted(int rightMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = rightMargin;
        return lp;
    }

    private LinearLayout column(String title, int color, int mode) {
        LinearLayout c = new LinearLayout(getContext());
        c.setOrientation(LinearLayout.VERTICAL);
        c.addView(section(title, color));
        for (GtaKeys.Action a : GtaKeys.of(mode)) {
            int k = keys.get(a);
            c.addView(row(a.name, GtaKeys.shortName(k), Theme.keyColor(k), v -> choose(a)));
        }
        return c;
    }

    private TextView section(String s, int color) {
        TextView t = text(s, 11, Theme.MEDIUM, color);
        t.setLetterSpacing(0.16f);
        t.setPadding(dp(2), dp(18), 0, dp(8));
        return t;
    }

    private LinearLayout rowShell(View.OnClickListener l) {
        LinearLayout r = new LinearLayout(getContext());
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(6), dp(10), dp(6));
        r.setMinimumHeight(dp(44));
        r.setBackground(Theme.surface(Theme.SURFACE, Theme.HAIR, dp(14), 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        r.setLayoutParams(lp);
        r.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); l.onClick(v); });
        return r;
    }

    private View row(String name, String value, int valueColor, View.OnClickListener l) {
        LinearLayout r = rowShell(l);
        r.addView(text(name, 15, Theme.REGULAR, Theme.TEXT),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = text(value, 14, Theme.MEDIUM, valueColor);
        v.setGravity(Gravity.CENTER);
        v.setMinWidth(dp(56));
        v.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable chip = new GradientDrawable();
        chip.setCornerRadius(dp(14));
        chip.setColor(Theme.SURFACE_HI);
        v.setBackground(chip);
        r.addView(v);
        return r;
    }

    /** Ligne avec interrupteur à glissière (état lisible d'un coup d'œil). */
    private View toggleRow(String name, boolean on, View.OnClickListener l) {
        LinearLayout r = rowShell(l);
        r.addView(text(name, 15, Theme.REGULAR, Theme.TEXT),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View sw = new View(getContext()) {
            final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(android.graphics.Canvas c) {
                float w = getWidth(), h = getHeight();
                p.setColor(on ? Theme.GREEN : Theme.SURFACE_HI);
                c.drawRoundRect(0, 0, w, h, h / 2, h / 2, p);
                p.setColor(Color.WHITE);
                c.drawCircle(on ? w - h / 2 : h / 2, h / 2, h / 2 - dp(3), p);
            }
        };
        r.addView(sw, new LinearLayout.LayoutParams(dp(50), dp(30)));
        return r;
    }

    private void choose(GtaKeys.Action a) {
        int cur = keys.get(a), sel = -1;
        String[] names = new String[GtaKeys.TARGETS.length];
        for (int i = 0; i < names.length; i++) {
            int tg = GtaKeys.TARGETS[i];
            names[i] = GtaKeys.longName(tg) + (tg == a.def ? "   (défaut)" : "");
            if (tg == cur) sel = i;
        }
        new AlertDialog.Builder(getContext(), AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle(a.name + (a.mode == GtaKeys.FOOT ? " · à pied" : " · en voiture"))
                .setSingleChoiceItems(names, sel, (d, which) -> {
                    keys.set(a, GtaKeys.TARGETS[which]);
                    d.dismiss();
                    build();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private static String tiltLabel(int deg) {
        return deg <= 20 ? "Vif · 20°" : deg <= 30 ? "Normal · 30°" : "Doux · 45°";
    }

    private void chooseTilt() {
        int[] ch = GtaKeys.TILT_CHOICES;
        String[] names = new String[ch.length];
        int sel = 1;
        for (int i = 0; i < ch.length; i++) {
            names[i] = tiltLabel(ch[i]);
            if (ch[i] == keys.tiltMax()) sel = i;
        }
        new AlertDialog.Builder(getContext(), AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Sensibilité de l'inclinaison")
                .setSingleChoiceItems(names, sel, (d, which) -> {
                    keys.setTiltMax(ch[which]);
                    d.dismiss();
                    build();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private TextView text(String s, int sp, Typeface face, int color) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(face);
        return t;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
