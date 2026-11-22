package com.dynamicagence.icontroller;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import java.util.ArrayList;
import java.util.List;

/**
 * Actions de l'écran GTA San Andreas et leur touche de manette (réassignable, sauvegardée).
 *
 * Chaque mode (à pied / en voiture) a ses propres actions : « Tirer » à pied et « Accélérer »
 * en voiture peuvent donc partager la même touche physique, comme dans le jeu.
 */
public final class GtaKeys {

    public static final int FOOT = 0, CAR = 1;

    // Cibles : 0..14 = bits HidGamepad.BTN_*, 100..103 = croix (haut, droite, bas, gauche), -1 = aucune.
    public static final int NONE = -1, HAT_UP = 100, HAT_RIGHT = 101, HAT_DOWN = 102, HAT_LEFT = 103;

    /** Ordre proposé dans l'écran de réglage. HOME (BTN_MODE) exclu : il renvoie à l'accueil de la TV. */
    public static final int[] TARGETS = {
            HidGamepad.BTN_A, HidGamepad.BTN_B, HidGamepad.BTN_X, HidGamepad.BTN_Y,
            HidGamepad.BTN_L1, HidGamepad.BTN_R1, HidGamepad.BTN_L2, HidGamepad.BTN_R2,
            HidGamepad.BTN_L3, HidGamepad.BTN_R3, HidGamepad.BTN_SELECT, HidGamepad.BTN_START,
            HAT_UP, HAT_RIGHT, HAT_DOWN, HAT_LEFT, NONE
    };

    public static final class Action {
        public final String id;      // clé de sauvegarde
        public final int mode;
        public final String name;    // nom complet (réglages)
        public final String label;   // libellé court sur le bouton
        public final int def;        // touche par défaut (À CONFIRMER EN JEU)
        public final boolean toggleMode; // Entrer / Sortir : peut basculer le mode
        Action(String id, int mode, String name, String label, int def, boolean toggleMode) {
            this.id = id; this.mode = mode; this.name = name; this.label = label; this.def = def;
            this.toggleMode = toggleMode;
        }
    }

    /*
     * Touches par défaut : SUPPOSITION, À CONFIRMER EN JEU.
     * Calquées sur la disposition manette Xbox 360 / PC de GTA SA (gâchettes = tir / visée à pied,
     * accélérateur / frein en voiture). Le portage Android peut différer : Driss teste en jeu
     * (et regarde les commandes manette du jeu s'il y en a), puis corrige dans l'écran « Touches GTA ».
     */
    public static final Action[] ACTIONS = {
            // ---- À pied ----
            new Action("pied.tirer",     FOOT, "Tirer / Frapper",        "TIRER",     HidGamepad.BTN_R2, false),
            new Action("pied.viser",     FOOT, "Viser",                  "VISER",     HidGamepad.BTN_L2, false),
            new Action("pied.sauter",    FOOT, "Sauter / Escalader",     "SAUTER",    HidGamepad.BTN_X, false),
            new Action("pied.sprint",    FOOT, "Sprinter / Nager",       "SPRINT",    HidGamepad.BTN_A, false),
            new Action("pied.entrer",    FOOT, "Entrer dans un véhicule","ENTRER",    HidGamepad.BTN_Y, true),
            new Action("pied.action",    FOOT, "Action / Parler",        "Action",    HidGamepad.BTN_B, false),
            new Action("pied.accroupi",  FOOT, "S'accroupir",            "Accroupi",  HidGamepad.BTN_L3, false),
            new Action("pied.arme_prec", FOOT, "Arme précédente",        "◄ Arme",    HidGamepad.BTN_L1, false),
            new Action("pied.arme_suiv", FOOT, "Arme suivante",          "Arme ►",    HidGamepad.BTN_R1, false),
            new Action("pied.derriere",  FOOT, "Regarder derrière",      "Derrière",  HidGamepad.BTN_R3, false),
            new Action("pied.non",       FOOT, "Répondre non",           "Non",       HAT_LEFT, false),
            new Action("pied.oui",       FOOT, "Répondre oui",           "Oui",       HAT_RIGHT, false),
            new Action("pied.camera",    FOOT, "Changer de vue",         "Vue",       HidGamepad.BTN_SELECT, false),
            new Action("pied.carte",     FOOT, "Carte / Pause",          "Carte",     HidGamepad.BTN_START, false),
            // ---- En voiture ----
            new Action("voiture.accel",  CAR,  "Accélérer",              "ACCÉLÉRER", HidGamepad.BTN_R2, false),
            new Action("voiture.frein",  CAR,  "Freiner / Reculer",      "FREINER",   HidGamepad.BTN_L2, false),
            new Action("voiture.main",   CAR,  "Frein à main",           "Frein à main", HidGamepad.BTN_R1, false),
            new Action("voiture.tirer",  CAR,  "Tirer / Nitro",          "TIRER",     HidGamepad.BTN_B, false),
            new Action("voiture.sortir", CAR,  "Sortir du véhicule",     "SORTIR",    HidGamepad.BTN_Y, true),
            new Action("voiture.klaxon", CAR,  "Klaxon",                 "Klaxon",    HidGamepad.BTN_L3, false),
            new Action("voiture.radio_prec", CAR, "Radio précédente",    "◄ Radio",   HAT_LEFT, false),
            new Action("voiture.radio_suiv", CAR, "Radio suivante",      "Radio ►",   HAT_RIGHT, false),
            new Action("voiture.mission",CAR,  "Mission annexe (taxi, ambulance…)", "Mission", HidGamepad.BTN_R3, false),
            new Action("voiture.camera", CAR,  "Changer de vue",         "Vue",       HidGamepad.BTN_SELECT, false),
            new Action("voiture.carte",  CAR,  "Carte / Pause",          "Carte",     HidGamepad.BTN_START, false),
    };

    public static final String PREFS = "gta";
    public static final String K_AUTO_MODE = "option.auto_mode";
    public static final String K_TILT = "option.inclinaison";
    public static final String K_TILT_MAX = "option.inclinaison_max";
    public static final int[] TILT_CHOICES = {20, 30, 45};

    private final SharedPreferences prefs;

    public GtaKeys(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static Action find(String id) {
        for (Action a : ACTIONS) if (a.id.equals(id)) return a;
        throw new IllegalArgumentException(id);
    }

    public static List<Action> of(int mode) {
        List<Action> l = new ArrayList<>();
        for (Action a : ACTIONS) if (a.mode == mode) l.add(a);
        return l;
    }

    public int get(Action a) { return prefs.getInt(a.id, a.def); }
    public void set(Action a, int target) { prefs.edit().putInt(a.id, target).apply(); }

    public void resetKeys() {
        SharedPreferences.Editor e = prefs.edit();
        for (Action a : ACTIONS) e.remove(a.id);
        e.apply();
    }

    public boolean autoMode() { return prefs.getBoolean(K_AUTO_MODE, false); }
    public void setAutoMode(boolean on) { prefs.edit().putBoolean(K_AUTO_MODE, on).apply(); }
    public boolean tilt() { return prefs.getBoolean(K_TILT, false); }
    public void setTilt(boolean on) { prefs.edit().putBoolean(K_TILT, on).apply(); }
    public int tiltMax() { return prefs.getInt(K_TILT_MAX, 30); }
    public void setTiltMax(int deg) { prefs.edit().putInt(K_TILT_MAX, deg).apply(); }

    // ---- Libellés des touches ----

    /** Nom court affiché sur les boutons (« RT », « A », « ► »). */
    public static String shortName(int t) {
        switch (t) {
            case HidGamepad.BTN_A: return "A";
            case HidGamepad.BTN_B: return "B";
            case HidGamepad.BTN_X: return "X";
            case HidGamepad.BTN_Y: return "Y";
            case HidGamepad.BTN_L1: return "LB";
            case HidGamepad.BTN_R1: return "RB";
            case HidGamepad.BTN_L2: return "LT";
            case HidGamepad.BTN_R2: return "RT";
            case HidGamepad.BTN_L3: return "LS";
            case HidGamepad.BTN_R3: return "RS";
            case HidGamepad.BTN_SELECT: return "VIEW";
            case HidGamepad.BTN_START: return "MENU";
            case HAT_UP: return "▲";
            case HAT_RIGHT: return "►";
            case HAT_DOWN: return "▼";
            case HAT_LEFT: return "◄";
            default: return "—";
        }
    }

    /** Nom complet pour la liste de choix. */
    public static String longName(int t) {
        switch (t) {
            case HidGamepad.BTN_A: return "A";
            case HidGamepad.BTN_B: return "B";
            case HidGamepad.BTN_X: return "X";
            case HidGamepad.BTN_Y: return "Y";
            case HidGamepad.BTN_L1: return "LB  (bouton de tranche gauche)";
            case HidGamepad.BTN_R1: return "RB  (bouton de tranche droit)";
            case HidGamepad.BTN_L2: return "LT  (gâchette gauche)";
            case HidGamepad.BTN_R2: return "RT  (gâchette droite)";
            case HidGamepad.BTN_L3: return "LS  (clic stick gauche)";
            case HidGamepad.BTN_R3: return "RS  (clic stick droit)";
            case HidGamepad.BTN_SELECT: return "View  (Select)";
            case HidGamepad.BTN_START: return "Menu  (Start)";
            case HAT_UP: return "Croix ▲ haut";
            case HAT_RIGHT: return "Croix ► droite";
            case HAT_DOWN: return "Croix ▼ bas";
            case HAT_LEFT: return "Croix ◄ gauche";
            default: return "Aucune touche";
        }
    }

    /** Couleur Xbox pour A/B/X/Y, gris clair sinon. */
    public static int color(int t) {
        switch (t) {
            case HidGamepad.BTN_A: return Color.rgb(96, 196, 46);
            case HidGamepad.BTN_B: return Color.rgb(230, 62, 66);
            case HidGamepad.BTN_X: return Color.rgb(54, 132, 230);
            case HidGamepad.BTN_Y: return Color.rgb(242, 196, 30);
            default: return Color.rgb(200, 204, 212);
        }
    }
}
