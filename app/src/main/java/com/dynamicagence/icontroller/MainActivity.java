package com.dynamicagence.icontroller;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SuppressLint("MissingPermission")
public class MainActivity extends Activity implements HidGamepad.Listener {

    private static final int REQ_PERMS = 1;
    private static final int REQ_ENABLE_BT = 2;

    private HidGamepad pad;
    private FrameLayout root;
    private View setupScreen;
    private GamepadView padView;
    private GtaView gtaView;
    private GtaSettingsView gtaSettings;
    private GtaKeys gtaKeys;
    private SharedPreferences prefs;
    private TextView statusText;
    private LinearLayout deviceList;
    private final Map<String, BluetoothDevice> devices = new LinkedHashMap<>();
    private BluetoothDevice pendingBond;
    private String lastStatus = "";

    // ---- Mode Wi-Fi ----
    private WifiLink wifi;
    private boolean wifiMode;
    private boolean wifiOn;                       // lien Wi-Fi établi (accusé reçu du récepteur)
    private EditText ipField;
    private LinearLayout wifiFound;
    private View btSection, wifiSection, btActions, wifiActions;
    private TextView segBt, segWifi, subtitle;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Theme.BG);
        getWindow().setNavigationBarColor(Theme.BG);
        pad = new HidGamepad(this, this);
        wifi = new WifiLink(this, wifiListener);

        root = new FrameLayout(this);
        root.setBackgroundColor(Theme.BG);
        prefs = getSharedPreferences("app", MODE_PRIVATE);
        wifiMode = "wifi".equals(prefs.getString("liaison", "bt"));
        setupScreen = buildSetup();
        padView = new GamepadView(this, pad);
        padView.setMenuListener(this::showSetup);
        gtaKeys = new GtaKeys(this);
        gtaView = new GtaView(this, pad, gtaKeys);
        gtaView.setListener(new GtaView.Listener() {
            @Override public void onBack() { showSetup(); }
            @Override public void onSettings() { showGtaSettings(); }
        });
        gtaSettings = new GtaSettingsView(this, gtaKeys, this::showGta);
        root.addView(setupScreen);
        root.addView(padView);
        root.addView(gtaView);
        root.addView(gtaSettings);
        padView.setVisibility(View.GONE);
        gtaView.setVisibility(View.GONE);
        gtaSettings.setVisibility(View.GONE);
        setContentView(root);

        // Mise au point (adb) : --es ecran gta|pad|touches, --ez zones true
        Intent it = getIntent();
        gtaView.debugHits = it.getBooleanExtra("zones", false);
        if (it.getBooleanExtra("voiture", false)) gtaView.setMode(GtaKeys.CAR);
        String ecran = it.getStringExtra("ecran");
        if ("gta".equals(ecran)) showGta();
        else if ("pad".equals(ecran)) showPad();
        else if ("touches".equals(ecran)) showGtaSettings();

        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(receiver, f);

        applyLink();
        if (wifiMode) {
            onStatus("Mode Wi-Fi : saisis l'adresse du récepteur.");
            if (it.getBooleanExtra("connecter", false)) connectWifi();
        } else if (hasPerms()) begin(); else requestPerms();
    }

    // ---- Permissions ----

    private String[] neededPerms() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean hasPerms() {
        for (String p : neededPerms())
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    private void requestPerms() { requestPermissions(neededPerms(), REQ_PERMS); }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code != REQ_PERMS) return;
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            onStatus("Autorisation « Appareils à proximité » refusée : impossible d'utiliser le Bluetooth.");
            return;
        }
        if (Build.VERSION.SDK_INT < 31 && !hasPerms())
            onStatus("Sans la localisation, la recherche d'appareils ne marchera pas (la connexion aux appareils déjà associés, oui).");
        begin();
    }

    private void begin() {
        if (wifiMode) return;
        BluetoothAdapter a = pad.getAdapter();
        if (a == null) { onStatus("Ce téléphone n'a pas de Bluetooth."); return; }
        if (!a.isEnabled()) {
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE_BT);
            return;
        }
        pad.start();
        refreshBonded();
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        if (code == REQ_ENABLE_BT) {
            if (result == RESULT_OK) begin();
            else onStatus("Le Bluetooth doit être activé.");
        }
    }

    // ---- Écran de connexion ----

    private View statusDot;

    /** Deux colonnes en paysage : identité + connexion à gauche, choix de l'écran + appareils à droite. */
    private View buildSetup() {
        LinearLayout split = new LinearLayout(this);
        split.setOrientation(LinearLayout.HORIZONTAL);
        split.setBackgroundColor(Theme.BG);

        // ---- Gauche ----
        ScrollView lsv = new ScrollView(this);
        lsv.setFillViewport(true);
        lsv.setVerticalScrollBarEnabled(false);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(dp(32), dp(24), dp(20), dp(24));
        lsv.addView(left);

        TextView title = text("SA Controller", 34, Theme.LIGHT, Theme.TEXT);
        title.setLetterSpacing(-0.01f);
        left.addView(title);
        subtitle = text("", 15, Theme.REGULAR, Theme.DIM);
        subtitle.setPadding(0, dp(4), 0, dp(16));
        left.addView(subtitle);

        // Choix de la liaison : Bluetooth ou Wi-Fi
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackground(Theme.surface(Theme.SURFACE, Theme.HAIR, dp(20), 1));
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        segBt = segment("Bluetooth", false);
        segWifi = segment("Wi-Fi", true);
        seg.addView(segBt, new LinearLayout.LayoutParams(0, dp(36), 1f));
        seg.addView(segWifi, new LinearLayout.LayoutParams(0, dp(36), 1f));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.bottomMargin = dp(18);
        left.addView(seg, sl);

        LinearLayout status = new LinearLayout(this);
        status.setOrientation(LinearLayout.HORIZONTAL);
        status.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = new View(this);
        statusDot.setBackground(dot(Theme.AMBER));
        status.addView(statusDot, new LinearLayout.LayoutParams(dp(9), dp(9)));
        statusText = text("Démarrage…", 15, Theme.REGULAR, Theme.TEXT);
        statusText.setPadding(dp(10), 0, 0, 0);
        status.addView(statusText);
        left.addView(status);

        View spacer = new View(this);
        left.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(0, dp(22), 0, 0);
        actions.addView(action("Chercher un appareil", true, v -> scan()));
        actions.addView(action("Rendre visible", false, v -> makeDiscoverable()));
        actions.addView(action("Déconnecter", false, v -> pad.disconnect()));
        btActions = actions;
        left.addView(actions);

        LinearLayout wactions = new LinearLayout(this);
        wactions.setOrientation(LinearLayout.VERTICAL);
        wactions.setPadding(0, dp(22), 0, 0);
        wactions.addView(action("Se connecter", true, v -> connectWifi()));
        wactions.addView(action("Chercher sur le réseau", false, v -> scanWifi()));
        wactions.addView(action("Déconnecter", false, v -> { wifi.stop(); }));
        wifiActions = wactions;
        left.addView(wactions);
        split.addView(lsv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.9f));

        // Filet vertical
        View hair = new View(this);
        hair.setBackgroundColor(Theme.HAIR);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(1, ViewGroup.LayoutParams.MATCH_PARENT);
        hl.topMargin = dp(24); hl.bottomMargin = dp(24);
        split.addView(hair, hl);

        // ---- Droite ----
        ScrollView rsv = new ScrollView(this);
        rsv.setVerticalScrollBarEnabled(false);
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(24), dp(24), dp(32), dp(24));
        rsv.addView(right);

        right.addView(label("JOUER"));
        right.addView(screenTile(Glyph.PAD, "Manette", "Toutes les touches, façon console",
                Theme.BLUE, v -> showPad()));
        right.addView(screenTile(Glyph.WHEEL, "GTA San Andreas", "Actions nommées · à pied et en voiture",
                Theme.AMBER, v -> showGta()));

        LinearLayout bt = new LinearLayout(this);
        bt.setOrientation(LinearLayout.VERTICAL);
        TextView listTitle = label("APPAREILS");
        listTitle.setPadding(dp(2), dp(22), 0, dp(8));
        bt.addView(listTitle);
        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        bt.addView(deviceList);

        TextView help = text("1  Ouvre les réglages Bluetooth de la TV ou du Mac.\n"
                + "2  Touche « Chercher un appareil », puis le nom de la TV ou du Mac. "
                + "Ou « Rendre visible », et associe le téléphone depuis la TV.\n"
                + "3  Une fois connecté, choisis Manette ou GTA San Andreas.", 13, Theme.REGULAR, Theme.DIM);
        help.setLineSpacing(0, 1.25f);
        help.setPadding(dp(2), dp(22), 0, 0);
        bt.addView(help);
        btSection = bt;
        right.addView(bt);

        LinearLayout wf = new LinearLayout(this);
        wf.setOrientation(LinearLayout.VERTICAL);
        TextView wTitle = label("RÉCEPTEUR");
        wTitle.setPadding(dp(2), dp(22), 0, dp(8));
        wf.addView(wTitle);
        ipField = new EditText(this);
        ipField.setHint("Adresse IP, ex. 192.168.1.20");
        ipField.setHintTextColor(Theme.FAINT);
        ipField.setTextColor(Theme.TEXT);
        ipField.setTextSize(16);
        ipField.setSingleLine(true);
        ipField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        ipField.setText(prefs.getString("wifiHote", ""));
        ipField.setPadding(dp(16), dp(12), dp(16), dp(12));
        ipField.setBackground(Theme.surface(Theme.SURFACE, Theme.HAIR, dp(16), 1));
        wf.addView(ipField, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        TextView fTitle = label("TROUVÉS SUR LE RÉSEAU");
        fTitle.setPadding(dp(2), dp(18), 0, dp(8));
        wf.addView(fTitle);
        wifiFound = new LinearLayout(this);
        wifiFound.setOrientation(LinearLayout.VERTICAL);
        wf.addView(wifiFound);
        TextView whelp = text("1  Lance le récepteur sur le Mac (voir le README, dossier recepteur-mac).\n"
                + "2  Le téléphone et le Mac doivent être sur le même Wi-Fi.\n"
                + "3  Touche « Chercher sur le réseau » puis le nom du Mac, ou saisis son adresse IP, "
                + "puis « Se connecter ».", 13, Theme.REGULAR, Theme.DIM);
        whelp.setLineSpacing(0, 1.25f);
        whelp.setPadding(dp(2), dp(22), 0, 0);
        wf.addView(whelp);
        wifiSection = wf;
        right.addView(wf);
        split.addView(rsv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f));
        return split;
    }

    private TextView segment(String label, boolean wifiSeg) {
        TextView t = text(label, 14, Theme.MEDIUM, Theme.DIM);
        t.setGravity(Gravity.CENTER);
        t.setOnClickListener(v -> setLink(wifiSeg));
        return t;
    }

    /** Bascule Bluetooth / Wi-Fi sans toucher au réglage de l'autre liaison. */
    private void setLink(boolean toWifi) {
        if (toWifi == wifiMode) return;
        wifiMode = toWifi;
        prefs.edit().putString("liaison", toWifi ? "wifi" : "bt").apply();
        if (toWifi) {
            pad.disconnect();            // Bluetooth : on lâche la TV, l'enregistrement HID reste prêt
            pad.setWifi(wifi);
        } else {
            wifi.stop();
            pad.setWifi(null);
            if (hasPerms()) begin(); else requestPerms();
        }
        applyLink();
    }

    /** Montre ce qui concerne la liaison choisie. */
    private void applyLink() {
        pad.setWifi(wifiMode ? wifi : null);
        btSection.setVisibility(wifiMode ? View.GONE : View.VISIBLE);
        btActions.setVisibility(wifiMode ? View.GONE : View.VISIBLE);
        wifiSection.setVisibility(wifiMode ? View.VISIBLE : View.GONE);
        wifiActions.setVisibility(wifiMode ? View.VISIBLE : View.GONE);
        subtitle.setText(wifiMode ? "Ton téléphone devient une manette Wi-Fi."
                : "Ton téléphone devient une manette Bluetooth.");
        styleSeg(segBt, !wifiMode);
        styleSeg(segWifi, wifiMode);
        updateDot();
        if (wifiMode) onStatus(wifi.isConnected() ? "Connecté en Wi-Fi à " + wifi.hostName()
                : "Mode Wi-Fi : saisis l'adresse du récepteur.");
        else onStatus(pad.getConnected() != null ? "Connecté à " + HidGamepad.name(pad.getConnected())
                : "Bluetooth : choisis un appareil.");
    }

    private void styleSeg(TextView t, boolean on) {
        t.setTextColor(on ? Color.WHITE : Theme.DIM);
        t.setBackground(on ? Theme.surface(Theme.BLUE, Theme.BLUE, dp(17), 0) : null);
    }

    private boolean linked() { return wifiMode ? wifiOn : pad.getConnected() != null; }

    private void updateDot() {
        if (statusDot != null) statusDot.setBackground(dot(linked() ? Theme.GREEN : Theme.AMBER));
        padView.setConnected(linked());
        gtaView.setConnected(linked());
    }

    private void connectWifi() {
        String h = ipField.getText().toString().trim();
        prefs.edit().putString("wifiHote", h).apply();
        wifiOn = false;
        wifi.start(h);
    }

    private void scanWifi() {
        wifiFound.removeAllViews();
        TextView t = text("Recherche en cours…", 14, Theme.REGULAR, Theme.DIM);
        t.setPadding(dp(2), 0, 0, 0);
        wifiFound.addView(t);
        onStatus("Recherche des récepteurs sur le réseau…");
        wifi.discover();
    }

    private final WifiLink.Listener wifiListener = new WifiLink.Listener() {
        @Override public void onWifiState(boolean connected, String message) {
            if (!wifiMode) return;
            boolean was = wifiOn;
            wifiOn = connected;
            onStatus(message);
            updateDot();
            if (connected && !was && setupScreen.getVisibility() == View.VISIBLE) {
                if ("gta".equals(prefs.getString("ecran", "pad"))) showGta(); else showPad();
            }
        }

        @Override public void onWifiFound(String name, String ip) {
            if (name == null) { // fin de recherche
                if (wifiFound.getChildCount() == 1 && wifiFound.getChildAt(0) instanceof TextView
                        && ((TextView) wifiFound.getChildAt(0)).getText().toString().startsWith("Recherche")) {
                    ((TextView) wifiFound.getChildAt(0)).setText("Aucun récepteur trouvé. Saisis l'adresse IP à la main.");
                }
                return;
            }
            if (wifiFound.getChildCount() == 1 && wifiFound.getChildAt(0) instanceof TextView
                    && !(wifiFound.getChildAt(0) instanceof LinearLayout)
                    && ((TextView) wifiFound.getChildAt(0)).getText().toString().startsWith("Recherche"))
                wifiFound.removeAllViews();
            TextView row = text(name + "   " + ip, 15, Theme.MEDIUM, Theme.TEXT);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            row.setMinHeight(dp(52));
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(Theme.surface(Theme.SURFACE, Theme.HAIR, dp(16), 1));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            row.setLayoutParams(lp);
            row.setOnClickListener(v -> { ipField.setText(ip); connectWifi(); });
            wifiFound.addView(row);
        }
    };

    private GradientDrawable dot(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private TextView text(String s, int sp, Typeface face, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(face);
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 11, Theme.MEDIUM, Theme.DIM);
        t.setLetterSpacing(0.16f);
        t.setPadding(dp(2), 0, 0, dp(8));
        return t;
    }

    private TextView action(String label, boolean primary, View.OnClickListener l) {
        TextView t = text(label, 15, Theme.MEDIUM, primary ? Color.WHITE : Theme.TEXT);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(46));
        t.setPadding(dp(18), dp(10), dp(18), dp(10));
        t.setBackground(Theme.surface(primary ? Theme.BLUE : Theme.SURFACE, Theme.HAIR, dp(23), primary ? 0 : 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); l.onClick(v); });
        return t;
    }

    /** Grande ligne de choix d'écran : pictogramme, titre, sous-titre, chevron. */
    private View screenTile(int glyph, String title, String sub, int color, View.OnClickListener l) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(dp(18), dp(14), dp(14), dp(14));
        c.setMinimumHeight(dp(84));
        c.setBackground(Theme.surface(Theme.SURFACE, Theme.HAIR, dp(20), 1));
        c.addView(new Glyph(this, glyph, color), new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(dp(16), 0, dp(8), 0);
        tx.addView(text(title, 18, Theme.MEDIUM, Theme.TEXT));
        TextView st = text(sub, 13, Theme.REGULAR, Theme.DIM);
        st.setPadding(0, dp(2), 0, 0);
        tx.addView(st);
        c.addView(tx, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        c.addView(new Glyph(this, Glyph.CHEVRON, Theme.FAINT), new LinearLayout.LayoutParams(dp(22), dp(22)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        c.setLayoutParams(lp);
        c.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); l.onClick(v); });
        return c;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void refreshBonded() {
        BluetoothAdapter a = pad.getAdapter();
        if (a == null) return;
        try {
            for (BluetoothDevice d : a.getBondedDevices()) devices.put(d.getAddress(), d);
        } catch (SecurityException ignored) { }
        renderDevices();
    }

    private void renderDevices() {
        deviceList.removeAllViews();
        BluetoothDevice cur = pad.getConnected();
        updateDot();
        List<BluetoothDevice> list = new ArrayList<>(devices.values());
        if (list.isEmpty()) {
            TextView t = text("Aucun appareil pour l'instant.", 14, Theme.REGULAR, Theme.DIM);
            t.setPadding(dp(2), 0, 0, 0);
            deviceList.addView(t);
        }
        for (BluetoothDevice d : list) {
            boolean bonded = d.getBondState() == BluetoothDevice.BOND_BONDED;
            boolean isCur = cur != null && cur.equals(d);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            row.setMinimumHeight(dp(52));
            row.setBackground(Theme.surface(Theme.SURFACE, isCur ? Theme.alpha(Theme.GREEN, 150) : Theme.HAIR,
                    dp(16), isCur ? 1.5f : 1));
            row.addView(text(HidGamepad.name(d), 15, isCur ? Theme.MEDIUM : Theme.REGULAR, Theme.TEXT),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView tag = text(isCur ? "Connecté" : bonded ? "Associé" : "À proximité", 13,
                    Theme.MEDIUM, isCur ? Theme.GREEN : Theme.DIM);
            row.addView(tag);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            row.setLayoutParams(lp);
            row.setOnClickListener(v -> pick(d));
            deviceList.addView(row);
        }
    }

    private void pick(BluetoothDevice d) {
        BluetoothAdapter a = pad.getAdapter();
        try { if (a != null && a.isDiscovering()) a.cancelDiscovery(); } catch (SecurityException ignored) { }
        if (d.getBondState() == BluetoothDevice.BOND_BONDED) {
            pad.connect(d);
        } else {
            pendingBond = d;
            onStatus("Association avec " + HidGamepad.name(d) + "… accepte sur les deux écrans.");
            if (!d.createBond()) pad.connect(d); // certains systèmes associent via la connexion HID
        }
    }

    private void makeDiscoverable() {
        Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
        startActivity(i);
    }

    private void scan() {
        BluetoothAdapter a = pad.getAdapter();
        if (a == null) return;
        try {
            if (a.isDiscovering()) a.cancelDiscovery();
            if (a.startDiscovery()) onStatus("Recherche des appareils (≈12 s)…");
            else onStatus("Recherche impossible (autorisation ou localisation désactivée ?).");
        } catch (SecurityException e) {
            onStatus("Autorisation Bluetooth manquante.");
            requestPerms();
        }
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String act = i.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(act)) {
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d != null) { devices.put(d.getAddress(), d); renderDevices(); }
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(act)) {
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int state = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
                if (d != null && d.equals(pendingBond)) {
                    if (state == BluetoothDevice.BOND_BONDED) { pendingBond = null; pad.connect(d); }
                    else if (state == BluetoothDevice.BOND_NONE) { pendingBond = null; onStatus("Association refusée ou annulée."); }
                }
                renderDevices();
            } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(act)) {
                int s = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                if (s == BluetoothAdapter.STATE_ON && hasPerms() && !wifiMode) { pad.start(); refreshBonded(); }
                else if (s == BluetoothAdapter.STATE_TURNING_OFF || s == BluetoothAdapter.STATE_OFF) pad.onBluetoothOff();
            }
        }
    };

    // ---- Bascule écrans ----

    /** Affiche un seul écran ; tout est relâché avant le changement. */
    private void show(View v) {
        padView.releaseAll();
        gtaView.setActive(false);
        setupScreen.setVisibility(v == setupScreen ? View.VISIBLE : View.GONE);
        padView.setVisibility(v == padView ? View.VISIBLE : View.GONE);
        gtaView.setVisibility(v == gtaView ? View.VISIBLE : View.GONE);
        gtaSettings.setVisibility(v == gtaSettings ? View.VISIBLE : View.GONE);
        immersive(v == padView || v == gtaView);
    }

    private void showPad() {
        show(padView);
        pad.setMode(GtaKeys.FOOT);
        padView.setStatus(lastStatus);
        prefs.edit().putString("ecran", "pad").apply();
    }

    private void showGta() {
        show(gtaView);
        gtaView.setStatus(lastStatus);
        gtaView.refreshKeys();
        pad.setMode(gtaView.getMode());
        gtaView.setActive(true);
        prefs.edit().putString("ecran", "gta").apply();
    }

    private void showGtaSettings() { show(gtaSettings); }

    private void showSetup() {
        show(setupScreen);
        renderDevices();
    }

    private boolean playing() {
        return padView.getVisibility() == View.VISIBLE || gtaView.getVisibility() == View.VISIBLE;
    }

    @SuppressWarnings("deprecation")
    private void immersive(boolean on) {
        View d = getWindow().getDecorView();
        d.setSystemUiVisibility(on
                ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                  | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                : 0);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && playing()) immersive(true);
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (gtaSettings.getVisibility() == View.VISIBLE) showGta();
        else if (playing()) showSetup();
        else super.onBackPressed();
    }

    @Override protected void onResume() {
        super.onResume();
        if (wifiMode) { if (gtaView.getVisibility() == View.VISIBLE) gtaView.setActive(true); return; }
        BluetoothAdapter a = pad.getAdapter();
        if (hasPerms() && a != null && a.isEnabled() && !pad.isStarted()) { pad.start(); refreshBonded(); }
        else pad.ensureRegistered();
        if (gtaView.getVisibility() == View.VISIBLE) gtaView.setActive(true);
    }

    @Override protected void onPause() {
        super.onPause();
        padView.releaseAll();
        gtaView.setActive(false);
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(receiver); } catch (Exception ignored) { }
        try {
            BluetoothAdapter a = pad.getAdapter();
            if (a != null && a.isDiscovering()) a.cancelDiscovery();
        } catch (SecurityException ignored) { }
        wifi.stop();
        pad.stop();
        super.onDestroy();
    }

    // ---- HidGamepad.Listener ----

    @Override public void onStatus(String message) {
        lastStatus = message;
        statusText.setText(message);
        padView.setStatus(message);
        gtaView.setStatus(message);
    }

    @Override public void onConnectionChanged(BluetoothDevice device, boolean connected) {
        if (connected) devices.put(device.getAddress(), device);
        updateDot();
        renderDevices();
        if (!wifiMode && connected && setupScreen.getVisibility() == View.VISIBLE) {
            if ("gta".equals(prefs.getString("ecran", "pad"))) showGta(); else showPad();
        }
    }
}
