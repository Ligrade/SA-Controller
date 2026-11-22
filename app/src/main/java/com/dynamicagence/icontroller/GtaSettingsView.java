package com.dynamicagence.icontroller;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
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

    private static final int TEXT = Color.rgb(230, 233, 240);
    private static final int DIM = Color.rgb(150, 156, 170);
    private static final int ROW = Color.rgb(26, 28, 34);
    private static final int BLUE = Color.rgb(64, 156, 255);
    private static final int AMBER = Color.rgb(255, 174, 66);

    private final GtaKeys keys;
    private final Listener listener;
    private final LinearLayout col;

    public GtaSettingsView(Context ctx, GtaKeys keys, Listener listener) {
        super(ctx);
        this.keys = keys;
        this.listener = listener;
        setBackgroundColor(Color.rgb(14, 15, 18));
        setFillViewport(true);
        col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        int pd = dp(20);
        col.setPadding(pd, dp(14), pd, pd);
        addView(col);
        build();
    }

    private void build() {
        col.removeAllViews();

        // En-tête : retour + titre
        LinearLayout head = new LinearLayout(getContext());
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(pill("‹  Retour au jeu", BLUE, Color.WHITE, v -> listener.onClose()));
        TextView title = text("Touches GTA San Andreas", 22, true, TEXT);
        title.setPadding(dp(16), 0, 0, 0);
        head.addView(title);
        col.addView(head);

        TextView note = text("Les touches par défaut sont une supposition, à confirmer en jeu. "
                + "Si une action ne réagit pas, essaie une autre touche (ou regarde les commandes manette "
                + "dans les réglages du jeu), puis touche l'action ici pour la changer. C'est enregistré tout de suite.", 13, false, DIM);
        note.setPadding(0, dp(10), 0, dp(12));
        col.addView(note);

        // Deux colonnes côte à côte (paysage)
        LinearLayout cols = new LinearLayout(getContext());
        cols.setOrientation(LinearLayout.HORIZONTAL);
        cols.addView(column("À PIED", BLUE, GtaKeys.FOOT), weighted(dp(8)));
        cols.addView(column("EN VOITURE", AMBER, GtaKeys.CAR), weighted(0));
        col.addView(cols);

        // Options
        col.addView(section("OPTIONS", DIM));
        col.addView(row("Changer de mode automatiquement avec Entrer / Sortir",
                keys.autoMode() ? "Oui" : "Non", keys.autoMode() ? AMBER : DIM, v -> {
                    keys.setAutoMode(!keys.autoMode());
                    build();
                }));
        col.addView(row("Conduite par inclinaison : angle pour braquer à fond",
                tiltLabel(keys.tiltMax()), TEXT, v -> chooseTilt()));
        TextView reset = pill("Remettre les touches par défaut", Color.rgb(38, 41, 50), TEXT, v ->
                new AlertDialog.Builder(getContext())
                        .setTitle("Remettre les touches par défaut ?")
                        .setMessage("Tes réglages de touches GTA seront effacés.")
                        .setNegativeButton("Annuler", null)
                        .setPositiveButton("Remettre", (d, w) -> { keys.resetKeys(); build(); })
                        .show());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        reset.setLayoutParams(lp);
        col.addView(reset);
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
            c.addView(row(a.name, GtaKeys.shortName(k), GtaKeys.color(k), v -> choose(a)));
        }
        return c;
    }

    private TextView section(String s, int color) {
        TextView t = text(s, 13, true, color);
        t.setLetterSpacing(0.15f);
        t.setPadding(dp(4), dp(10), 0, dp(6));
        return t;
    }

    private View row(String name, String value, int valueColor, View.OnClickListener l) {
        LinearLayout r = new LinearLayout(getContext());
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), dp(10), dp(10), dp(10));
        r.setMinimumHeight(dp(48));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(ROW);
        r.setBackground(bg);
        TextView n = text(name, 15, false, TEXT);
        r.addView(n, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = text(value, 14, true, valueColor);
        v.setGravity(Gravity.CENTER);
        v.setMinWidth(dp(58));
        v.setPadding(dp(10), dp(5), dp(10), dp(5));
        GradientDrawable chip = new GradientDrawable();
        chip.setCornerRadius(dp(14));
        chip.setColor(Color.rgb(10, 11, 13));
        chip.setStroke(dp(1), Color.rgb(60, 64, 74));
        v.setBackground(chip);
        r.addView(v);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        r.setLayoutParams(lp);
        r.setOnClickListener(l);
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
        new AlertDialog.Builder(getContext())
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
        new AlertDialog.Builder(getContext())
                .setTitle("Sensibilité de l'inclinaison")
                .setSingleChoiceItems(names, sel, (d, which) -> {
                    keys.setTiltMax(ch[which]);
                    d.dismiss();
                    build();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private TextView pill(String label, int bgColor, int fg, View.OnClickListener l) {
        TextView t = text(label, 14, true, fg);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), dp(10), dp(16), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(22));
        bg.setColor(bgColor);
        t.setBackground(bg);
        t.setOnClickListener(l);
        return t;
    }

    private TextView text(String s, int sp, boolean bold, int color) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
