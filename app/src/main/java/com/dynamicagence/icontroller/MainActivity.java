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
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
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

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        pad = new HidGamepad(this, this);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(14, 15, 18));
        setupScreen = buildSetup();
        padView = new GamepadView(this, pad);
        padView.setMenuListener(this::showSetup);
        prefs = getSharedPreferences("app", MODE_PRIVATE);
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

        if (hasPerms()) begin(); else requestPerms();
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

    private View buildSetup() {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        col.setPadding(p, p, p, p);
        sv.addView(col);

        TextView title = text("SA Controller", 26, true);
        col.addView(title);
        TextView sub = text("Ton téléphone devient une manette Bluetooth.", 14, false);
        sub.setTextColor(Color.rgb(150, 156, 170));
        col.addView(sub);

        statusText = text("Démarrage…", 15, false);
        statusText.setTextColor(Color.rgb(64, 156, 255));
        statusText.setPadding(0, dp(14), 0, dp(14));
        col.addView(statusText);

        LinearLayout screens = new LinearLayout(this);
        screens.setOrientation(LinearLayout.HORIZONTAL);
        screens.addView(screenCard("Manette", "Toutes les touches, façon console", Color.rgb(64, 156, 255),
                v -> showPad()));
        screens.addView(screenCard("GTA San Andreas", "Boutons par action · à pied / en voiture",
                Color.rgb(255, 174, 66), v -> showGta()));
        col.addView(screens);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(12), 0, 0);
        row.addView(action("Rendre visible", false, v -> makeDiscoverable()));
        row.addView(action("Chercher", false, v -> scan()));
        row.addView(action("Déconnecter", false, v -> pad.disconnect()));
        col.addView(row);

        TextView help = text("1. Sur la TV ou le Mac, ouvre les réglages Bluetooth.\n"
                + "2. Ici, touche « Chercher », puis touche le nom de la TV / du Mac (ou « Rendre visible » "
                + "et associe le téléphone depuis la TV).\n"
                + "3. Une fois « Connecté », choisis « Manette » ou « GTA San Andreas ».", 13, false);
        help.setTextColor(Color.rgb(150, 156, 170));
        help.setPadding(0, dp(14), 0, dp(8));
        col.addView(help);

        TextView listTitle = text("Appareils", 16, true);
        listTitle.setPadding(0, dp(8), 0, dp(6));
        col.addView(listTitle);
        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        col.addView(deviceList);
        return sv;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.rgb(230, 233, 240));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView action(String label, boolean primary, View.OnClickListener l) {
        TextView t = text(label, 14, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(22));
        bg.setColor(primary ? Color.rgb(64, 156, 255) : Color.rgb(38, 41, 50));
        t.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(10);
        t.setLayoutParams(lp);
        t.setOnClickListener(l);
        return t;
    }

    /** Grande carte de choix d'écran. */
    private View screenCard(String title, String sub, int color, View.OnClickListener l) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(14), dp(18), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(Color.rgb(26, 28, 34));
        bg.setStroke(dp(2), color);
        c.setBackground(bg);
        TextView t = text(title, 18, true);
        t.setTextColor(color);
        c.addView(t);
        TextView st = text(sub, 13, false);
        st.setTextColor(Color.rgb(150, 156, 170));
        c.addView(st);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(10);
        c.setLayoutParams(lp);
        c.setOnClickListener(l);
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
        List<BluetoothDevice> list = new ArrayList<>(devices.values());
        if (list.isEmpty()) {
            TextView t = text("Aucun appareil. Touche « Chercher ».", 14, false);
            t.setTextColor(Color.rgb(150, 156, 170));
            deviceList.addView(t);
        }
        for (BluetoothDevice d : list) {
            boolean bonded = d.getBondState() == BluetoothDevice.BOND_BONDED;
            boolean isCur = cur != null && cur.equals(d);
            String tag = isCur ? "  ● connecté" : bonded ? "  · associé" : "  · à proximité";
            TextView t = text(HidGamepad.name(d) + tag, 15, isCur);
            t.setPadding(dp(14), dp(12), dp(14), dp(12));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(10));
            bg.setColor(isCur ? Color.rgb(26, 52, 86) : Color.rgb(26, 28, 34));
            t.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(6);
            t.setLayoutParams(lp);
            t.setOnClickListener(v -> pick(d));
            deviceList.addView(t);
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
                if (s == BluetoothAdapter.STATE_ON && !pad.isRegistered()) pad.start();
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
        padView.setStatus(lastStatus);
        prefs.edit().putString("ecran", "pad").apply();
    }

    private void showGta() {
        show(gtaView);
        gtaView.setStatus(lastStatus);
        gtaView.refreshKeys();
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
        pad.ensureRegistered();
        if (gtaView.getVisibility() == View.VISIBLE) gtaView.setActive(true);
    }

    @Override protected void onPause() {
        super.onPause();
        padView.releaseAll();
        gtaView.setActive(false);
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(receiver); } catch (Exception ignored) { }
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
        padView.setConnected(pad.getConnected() != null);
        gtaView.setConnected(pad.getConnected() != null);
        renderDevices();
        if (connected && setupScreen.getVisibility() == View.VISIBLE) {
            if ("gta".equals(prefs.getString("ecran", "pad"))) showGta(); else showPad();
        }
    }
}
