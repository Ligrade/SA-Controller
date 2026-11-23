package com.dynamicagence.icontroller;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Fait apparaître le téléphone comme une manette Bluetooth HID standard.
 *
 * Rapport (Report ID 1, 9 octets sans l'ID) :
 *  [0-1] 16 boutons (bit 0 = bouton HID 1)
 *  [2]   croix directionnelle (hat 0..7, 8 = relâché) sur 4 bits + 4 bits de bourrage
 *  [3-6] X, Y (stick gauche), Z, Rz (stick droit), signés -127..127
 *  [7-8] Accélérateur (R2), Frein (L2), 0..255
 *
 * Ordre des boutons : sous Linux/Android, le bouton HID n devient BTN_GAMEPAD + (n-1) :
 * 1=A 2=B 3=C 4=X 5=Y 6=Z 7=L1 8=R1 9=L2 10=R2 11=Select 12=Start 13=Mode 14=L3 15=R3.
 * On saute donc volontairement C et Z.
 */
@SuppressLint("MissingPermission")
public class HidGamepad {

    // Index de bit (= numéro de bouton HID - 1)
    public static final int BTN_A = 0, BTN_B = 1, BTN_X = 3, BTN_Y = 4,
            BTN_L1 = 6, BTN_R1 = 7, BTN_L2 = 8, BTN_R2 = 9,
            BTN_SELECT = 10, BTN_START = 11, BTN_MODE = 12, BTN_L3 = 13, BTN_R3 = 14;

    public static final int HAT_CENTER = 8;

    private static final byte REPORT_ID = 1;

    private static final byte[] DESCRIPTOR = {
            0x05, 0x01,                   // Usage Page (Generic Desktop)
            0x09, 0x05,                   // Usage (Game Pad)
            (byte) 0xA1, 0x01,            // Collection (Application)
            (byte) 0x85, REPORT_ID,       //   Report ID (1)
            // 16 boutons
            0x05, 0x09,                   //   Usage Page (Button)
            0x19, 0x01,                   //   Usage Minimum (1)
            0x29, 0x10,                   //   Usage Maximum (16)
            0x15, 0x00,                   //   Logical Minimum (0)
            0x25, 0x01,                   //   Logical Maximum (1)
            0x75, 0x01,                   //   Report Size (1)
            (byte) 0x95, 0x10,            //   Report Count (16)
            (byte) 0x81, 0x02,            //   Input (Data,Var,Abs)
            // Croix (hat switch)
            0x05, 0x01,                   //   Usage Page (Generic Desktop)
            0x09, 0x39,                   //   Usage (Hat switch)
            0x15, 0x00,                   //   Logical Minimum (0)
            0x25, 0x07,                   //   Logical Maximum (7)
            0x35, 0x00,                   //   Physical Minimum (0)
            0x46, 0x3B, 0x01,             //   Physical Maximum (315)
            0x65, 0x14,                   //   Unit (Degrees)
            0x75, 0x04,                   //   Report Size (4)
            (byte) 0x95, 0x01,            //   Report Count (1)
            (byte) 0x81, 0x42,            //   Input (Data,Var,Abs,Null)
            0x65, 0x00,                   //   Unit (None)
            0x35, 0x00,                   //   Physical Minimum (0) — remis à zéro pour les axes suivants
            0x45, 0x00,                   //   Physical Maximum (0)
            0x75, 0x04,                   //   Report Size (4)
            (byte) 0x95, 0x01,            //   Report Count (1)
            (byte) 0x81, 0x03,            //   Input (Const) — bourrage
            // Sticks
            0x09, 0x30,                   //   Usage (X)
            0x09, 0x31,                   //   Usage (Y)
            0x09, 0x32,                   //   Usage (Z)
            0x09, 0x35,                   //   Usage (Rz)
            0x15, (byte) 0x81,            //   Logical Minimum (-127)
            0x25, 0x7F,                   //   Logical Maximum (127)
            0x75, 0x08,                   //   Report Size (8)
            (byte) 0x95, 0x04,            //   Report Count (4)
            (byte) 0x81, 0x02,            //   Input (Data,Var,Abs)
            // Gâchettes analogiques
            0x05, 0x02,                   //   Usage Page (Simulation Controls)
            0x09, (byte) 0xC4,            //   Usage (Accelerator)
            0x09, (byte) 0xC5,            //   Usage (Brake)
            0x15, 0x00,                   //   Logical Minimum (0)
            0x26, (byte) 0xFF, 0x00,      //   Logical Maximum (255)
            0x75, 0x08,                   //   Report Size (8)
            (byte) 0x95, 0x02,            //   Report Count (2)
            (byte) 0x81, 0x02,            //   Input (Data,Var,Abs)
            (byte) 0xC0                   // End Collection
    };

    public interface Listener {
        void onStatus(String message);
        void onConnectionChanged(BluetoothDevice device, boolean connected);
    }

    private final Context context;
    private final Listener listener;
    private final BluetoothAdapter adapter;
    private final HandlerThread thread;
    private final Handler handler;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Executor executor;

    private volatile BluetoothHidDevice hid;
    private volatile boolean registered;
    private volatile boolean registering;
    private volatile boolean proxyPending;
    private volatile boolean stopped;
    private volatile BluetoothDevice connected;
    private volatile WifiLink wifi;   // non nul = mode Wi-Fi : les rapports partent en UDP au lieu du Bluetooth
    private BluetoothDevice pendingConnect;
    private BluetoothDevice lastHost;

    private final Object lock = new Object();
    private boolean flushPending;
    private long lastSendMs;
    private static final long MIN_INTERVAL_MS = 8;

    // État courant de la manette
    private int buttons;
    private int hat = HAT_CENTER;
    private int lx, ly, rx, ry, gas, brake;
    private final byte[] report = new byte[9];

    public HidGamepad(Context ctx, Listener listener) {
        this.context = ctx.getApplicationContext();
        this.listener = listener;
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = bm != null ? bm.getAdapter() : null;
        thread = new HandlerThread("hid");
        thread.start();
        handler = new Handler(thread.getLooper());
        executor = handler::post;
    }

    /** Active (lien non nul) ou coupe (null) le mode Wi-Fi. Le Bluetooth reste intact quand il est nul. */
    public void setWifi(WifiLink w) { wifi = w; if (w != null) sendNow(); }
    public void setMode(int m) { WifiLink w = wifi; if (w != null) w.setMode(m); modeValue = m; }
    private volatile int modeValue;
    private boolean canSend() { WifiLink w = wifi; return w != null ? w.isActive() : (hid != null && connected != null); }

    public BluetoothAdapter getAdapter() { return adapter; }
    public boolean isRegistered() { return registered; }
    /** Vrai si le profil HID est ouvert ou en cours d'ouverture. */
    public boolean isStarted() { return hid != null || proxyPending; }
    public BluetoothDevice getConnected() { return connected; }

    private void status(String s) { main.post(() -> { if (!stopped) listener.onStatus(s); }); }

    private final Runnable noResponse = () -> {
        if (!registered && !stopped) status("Pas de réponse du profil HID. Le fabricant l'a peut-être désactivé. "
                + "Essaie : Paramètres › Options développeur, couper/rallumer le Bluetooth.");
    };

    /** Remet l'état « connecté » à zéro et prévient l'interface (Bluetooth coupé, profil fermé). */
    private void resetConnection() {
        synchronized (lock) { flushPending = false; }
        handler.removeCallbacks(flush);
        pendingConnect = null;
        if (connected != null) {
            final BluetoothDevice d = connected;
            connected = null;
            main.post(() -> { if (!stopped) listener.onConnectionChanged(d, false); });
        }
    }

    /** Le Bluetooth vient de passer à l'arrêt : plus rien n'est connecté ni enregistré. */
    public void onBluetoothOff() {
        registered = false;
        registering = false;
        resetConnection();
        status("Bluetooth désactivé.");
    }

    /** Ouvre le profil HID Device et enregistre la manette. Sans effet s'il est déjà ouvert. */
    public void start() {
        if (stopped) return;
        if (adapter == null) { status("Ce téléphone n'a pas de Bluetooth."); return; }
        if (!adapter.isEnabled()) { status("Active le Bluetooth puis relance."); return; }
        if (hid != null || proxyPending) { ensureRegistered(); return; }
        applyBluetoothName();
        status("Ouverture du profil manette…");
        boolean ok;
        try {
            ok = adapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
                @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (profile != BluetoothProfile.HID_DEVICE) return;
                    proxyPending = false;
                    if (stopped) { closeProxy(proxy); return; }
                    BluetoothHidDevice old = hid;
                    if (old != null && old != proxy) closeProxy(old);
                    hid = (BluetoothHidDevice) proxy;
                    register();
                }
                @Override public void onServiceDisconnected(int profile) {
                    if (profile != BluetoothProfile.HID_DEVICE) return;
                    proxyPending = false;
                    hid = null;
                    registered = false;
                    registering = false;
                    resetConnection();
                    status("Profil manette fermé par le système.");
                }
            }, BluetoothProfile.HID_DEVICE);
        } catch (SecurityException e) {
            status("Autorisation Bluetooth manquante.");
            return;
        }
        if (!ok) {
            status("Ce téléphone ne propose pas le mode « périphérique HID » (désactivé par le fabricant).");
            return;
        }
        proxyPending = true;
        main.removeCallbacks(noResponse);
        main.postDelayed(noResponse, 6000);
    }

    private void closeProxy(BluetoothProfile proxy) {
        try { adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy); }
        catch (SecurityException ignored) { }
    }

    /** À appeler au retour au premier plan : Android retire l'enregistrement HID en arrière-plan. */
    public void ensureRegistered() {
        if (stopped || hid == null || registered) return;
        if (pendingConnect == null) pendingConnect = lastHost;
        status("Réactivation de la manette…");
        register();
    }

    private void register() {
        BluetoothHidDevice h = hid;
        if (stopped || h == null || registering) return;
        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                "iController", "Manette iController", "Ligrade",
                (byte) (BluetoothHidDevice.SUBCLASS1_NONE | BluetoothHidDevice.SUBCLASS2_GAMEPAD),
                DESCRIPTOR);
        registering = true;
        boolean ok;
        try {
            ok = h.registerApp(sdp, null, null, executor, callback);
        } catch (SecurityException e) {
            registering = false;
            status("Autorisation Bluetooth manquante.");
            return;
        }
        if (!ok) registering = false;
        if (!ok) status("Enregistrement refusé. Une autre appli utilise peut-être déjà le mode HID.");
    }

    private final BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
        @Override public void onAppStatusChanged(BluetoothDevice plugged, boolean reg) {
            registered = reg;
            registering = false;
            BluetoothHidDevice h = hid;
            if (reg && h != null) {
                status("Manette prête. Connecte-toi à la TV ou au Mac.");
                if (pendingConnect != null) { BluetoothDevice d = pendingConnect; pendingConnect = null; connect(d); }
                else if (plugged != null) h.connect(plugged);
            } else {
                status("Manette en pause (appli en arrière-plan). Elle se réactive au retour.");
            }
        }

        @Override public void onConnectionStateChanged(BluetoothDevice device, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                connected = device;
                lastHost = device;
                status("Connecté à " + name(device));
                main.post(() -> { if (!stopped) listener.onConnectionChanged(device, true); });
                sendNow();
            } else if (state == BluetoothProfile.STATE_CONNECTING) {
                status("Connexion à " + name(device) + "…");
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if (device.equals(connected)) connected = null;
                status("Déconnecté de " + name(device));
                main.post(() -> { if (!stopped) listener.onConnectionChanged(device, false); });
            }
        }

        @Override public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {
            BluetoothHidDevice h = hid;
            if (h == null) return;
            if (type == BluetoothHidDevice.REPORT_TYPE_INPUT) {
                byte[] data;
                synchronized (lock) { fill(); data = report.clone(); }
                h.replyReport(device, type, REPORT_ID, data);
            } else {
                h.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ);
            }
        }

        @Override public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] data) {
            BluetoothHidDevice h = hid;
            if (h != null) h.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS);
        }
    };

    public static String name(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n != null ? n : d.getAddress();
        } catch (SecurityException e) {
            return d.getAddress();
        }
    }

    public void connect(BluetoothDevice device) {
        BluetoothHidDevice h = hid;
        if (h == null) { pendingConnect = device; status("Manette pas encore prête…"); return; }
        if (!registered) { pendingConnect = device; status("Réactivation de la manette…"); register(); return; }
        BluetoothDevice cur = connected;
        if (cur != null && !cur.equals(device)) h.disconnect(cur);
        status("Connexion à " + name(device) + "…");
        if (!h.connect(device)) status("Connexion refusée par le système.");
    }

    public void disconnect() {
        lastHost = null;      // déconnexion voulue : pas de reconnexion automatique
        pendingConnect = null;
        BluetoothHidDevice h = hid;
        BluetoothDevice cur = connected;
        if (h != null && cur != null) h.disconnect(cur);
    }

    public List<BluetoothDevice> connectedHosts() {
        BluetoothHidDevice h = hid;
        return h != null ? h.getConnectedDevices() : java.util.Collections.emptyList();
    }

    /** Nom Bluetooth vu par la TV / le Mac pendant l'utilisation ; l'ancien est remis en quittant. */
    public static final String BT_NAME = "iController";
    private String originalName;

    private SharedPreferences prefs() { return context.getSharedPreferences("app", Context.MODE_PRIVATE); }

    private void applyBluetoothName() {
        try {
            String cur = adapter.getName();
            if (BT_NAME.equals(cur)) {
                // Appli tuée au précédent lancement : on reprend le nom d'origine sauvegardé
                if (originalName == null) originalName = prefs().getString("btNameOrigine", null);
            } else if (cur != null) {
                originalName = cur;
                prefs().edit().putString("btNameOrigine", cur).apply();
                adapter.setName(BT_NAME);
            }
        } catch (SecurityException ignored) { }
    }

    private void restoreBluetoothName() {
        try {
            if (originalName != null && adapter != null) {
                adapter.setName(originalName);
                prefs().edit().remove("btNameOrigine").apply();
            }
        } catch (SecurityException ignored) { }
        originalName = null;
    }

    public void stop() {
        stopped = true;
        main.removeCallbacksAndMessages(null);
        handler.removeCallbacks(flush);
        restoreBluetoothName();
        BluetoothHidDevice h = hid;
        hid = null;
        registered = false;
        registering = false;
        if (h != null) {
            try {
                BluetoothDevice cur = connected;
                if (cur != null) h.disconnect(cur);
                h.unregisterApp();
                adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, h);
            } catch (SecurityException ignored) { }
        }
        connected = null;
        thread.quitSafely();
    }

    // ---- État de la manette ----

    public void setButton(int bit, boolean down) {
        synchronized (lock) {
            buttons = down ? (buttons | (1 << bit)) : (buttons & ~(1 << bit));
            if (bit == BTN_R2) gas = down ? 255 : 0;
            if (bit == BTN_L2) brake = down ? 255 : 0;
        }
        sendNow();
    }

    public void setHat(int h) {
        synchronized (lock) {
            if (h == hat) return;
            hat = h;
        }
        sendNow();
    }

    public void setLeftStick(float x, float y) {
        int nx = axis(x), ny = axis(y);
        synchronized (lock) {
            if (nx == lx && ny == ly) return;
            lx = nx; ly = ny;
        }
        sendSoon();
    }

    public void setRightStick(float x, float y) {
        int nx = axis(x), ny = axis(y);
        synchronized (lock) {
            if (nx == rx && ny == ry) return;
            rx = nx; ry = ny;
        }
        sendSoon();
    }

    private static int axis(float v) {
        return Math.round(Math.max(-1f, Math.min(1f, v)) * 127f);
    }

    private void fill() {
        report[0] = (byte) (buttons & 0xFF);
        report[1] = (byte) ((buttons >> 8) & 0xFF);
        report[2] = (byte) (hat & 0x0F);
        report[3] = (byte) lx;
        report[4] = (byte) ly;
        report[5] = (byte) rx;
        report[6] = (byte) ry;
        report[7] = (byte) gas;
        report[8] = (byte) brake;
    }

    /** Boutons / croix : photo de l'état immédiate, pour ne jamais perdre un appui bref. */
    private void sendNow() {
        if (!canSend()) return;
        final byte[] data;
        synchronized (lock) {
            flushPending = false;
            fill();
            data = report.clone();
        }
        handler.removeCallbacks(flush);
        handler.post(() -> transmit(data));
    }

    /** Sticks : on regroupe les mouvements (≈120 rapports/s max) pour éviter la latence. */
    private void sendSoon() {
        if (!canSend()) return;
        synchronized (lock) {
            if (flushPending) return;
            flushPending = true;
        }
        long wait = Math.max(0, MIN_INTERVAL_MS - (SystemClock.uptimeMillis() - lastSendMs));
        handler.postDelayed(flush, wait);
    }

    private final Runnable flush = () -> {
        final byte[] data;
        synchronized (lock) {
            if (!flushPending) return;
            flushPending = false;
            fill();
            data = report.clone();
        }
        transmit(data);
    };

    private void transmit(byte[] data) {
        WifiLink w = wifi;
        if (w != null) { w.setMode(modeValue); w.send(data); lastSendMs = SystemClock.uptimeMillis(); return; }
        BluetoothHidDevice h = hid;
        BluetoothDevice target = connected;
        if (h == null || target == null) return;
        h.sendReport(target, REPORT_ID, data);
        lastSendMs = SystemClock.uptimeMillis();
    }
}
