package com.dynamicagence.icontroller;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Liaison Wi-Fi (réseau local) vers un récepteur SA Controller, en UDP.
 *
 * Protocole v1 (port UDP 38492), tout en octets :
 *  - Manette -> récepteur, 16 octets : 'S' 'A' 'C' 0x01, seq (u16, grand-boutisme), mode (0 à pied, 1 en voiture),
 *    puis les 9 octets du rapport HID de HidGamepad (boutons x2, croix, X, Y, Z, Rz, gaz, frein).
 *    Chaque paquet est l'état COMPLET de la manette : un paquet perdu ne laisse jamais une touche coincée.
 *    L'état est renvoyé toutes les 50 ms même sans changement (battement).
 *  - Récepteur -> manette : 'S' 'A' 'C' 'A' + nom (UTF-8), en réponse à chaque paquet d'état (accusé de réception).
 *  - Découverte : manette envoie 'S' 'A' 'C' '?' en diffusion ; le récepteur répond 'S' 'A' 'C' '!' + nom.
 */
public class WifiLink {

    public static final int PORT = 38492;
    private static final int HEARTBEAT_MS = 50;
    private static final int ACK_TIMEOUT_MS = 1500;

    public interface Listener {
        void onWifiState(boolean connected, String message);
        void onWifiFound(String name, String ip);
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private HandlerThread thread;
    private Handler handler;
    private volatile DatagramSocket socket;
    private volatile InetAddress target;
    private volatile boolean running;
    private volatile boolean connected;
    private volatile long lastAck;
    private volatile String hostName = "";
    private volatile int mode;
    private final byte[] report = new byte[9];
    private int seq;
    private WifiManager.WifiLock lock;

    public WifiLink(Context ctx, Listener l) {
        this.context = ctx.getApplicationContext();
        this.listener = l;
    }

    public boolean isActive() { return running; }
    public boolean isConnected() { return running && connected; }
    public String hostName() { return hostName; }

    // ---- Connexion ----

    public void start(String host) {
        stop();
        final String h = host.trim();
        if (h.isEmpty()) { post(false, "Saisis l'adresse IP du récepteur."); return; }
        running = true;
        connected = false;
        thread = new HandlerThread("wifi");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(() -> open(h));
    }

    private void open(String host) {
        try {
            target = InetAddress.getByName(host);
            DatagramSocket s = new DatagramSocket();
            s.setSoTimeout(200);
            socket = s;
        } catch (Exception e) {
            running = false;
            post(false, "Adresse invalide ou réseau indisponible.");
            return;
        }
        acquireLock();
        post(false, "Connexion à " + host + "…");
        Thread rx = new Thread(this::receiveLoop, "wifi-rx");
        rx.setDaemon(true);
        rx.start();
        beat();
    }

    private void beat() {
        if (!running) return;
        sendState();
        long now = SystemClock.uptimeMillis();
        if (connected && now - lastAck > ACK_TIMEOUT_MS) {
            connected = false;
            post(false, "Récepteur injoignable (" + hostName + "). Nouvelle tentative…");
        }
        handler.postDelayed(this::beat, HEARTBEAT_MS);
    }

    private void receiveLoop() {
        byte[] buf = new byte[256];
        while (running) {
            DatagramSocket s = socket;
            if (s == null) return;
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.receive(p);
                if (p.getLength() >= 4 && buf[0] == 'S' && buf[1] == 'A' && buf[2] == 'C' && buf[3] == 'A') {
                    lastAck = SystemClock.uptimeMillis();
                    String name = new String(buf, 4, p.getLength() - 4, StandardCharsets.UTF_8);
                    hostName = name;
                    if (!connected) {
                        connected = true;
                        post(true, "Connecté en Wi-Fi à " + name);
                    }
                }
            } catch (SocketTimeoutException ignored) {
            } catch (Exception e) {
                return;
            }
        }
    }

    public void stop() {
        boolean was = running;
        running = false;
        connected = false;
        final DatagramSocket s = socket;
        final InetAddress t = target;
        socket = null;
        if (thread != null) { thread.quitSafely(); thread = null; }
        releaseLock();
        if (s != null) {
            // Manette au repos, envoyée plusieurs fois (hors thread principal) : rien ne reste enfoncé côté récepteur
            final byte[] p = new byte[16];
            p[0] = 'S'; p[1] = 'A'; p[2] = 'C'; p[3] = 1; int n = seq + 1; p[4] = (byte) (n >> 8); p[5] = (byte) n;
            p[6] = (byte) mode; p[7 + 2] = (byte) HidGamepad.HAT_CENTER;
            new Thread(() -> {
                if (was && t != null)
                    for (int i = 0; i < 3; i++)
                        try { s.send(new DatagramPacket(p, p.length, t, PORT)); } catch (Exception ignored) { }
                s.close();
            }).start();
        }
        if (was) post(false, "Wi-Fi déconnecté.");
    }

    // ---- Envoi ----

    public void setMode(int m) { mode = m; }

    /** Appelé par HidGamepad à chaque nouvel état. */
    public void send(byte[] data) {
        synchronized (report) { System.arraycopy(data, 0, report, 0, 9); }
        Handler h = handler;
        if (running && h != null) h.post(this::sendState);
    }

    private void sendState() {
        DatagramSocket s = socket;
        InetAddress t = target;
        if (s == null || t == null) return;
        byte[] p = new byte[16];
        p[0] = 'S'; p[1] = 'A'; p[2] = 'C'; p[3] = 1;
        int n = seq++;
        p[4] = (byte) (n >> 8); p[5] = (byte) n;
        p[6] = (byte) mode;
        synchronized (report) { System.arraycopy(report, 0, p, 7, 9); }
        try { s.send(new DatagramPacket(p, p.length, t, PORT)); } catch (Exception ignored) { }
    }

    // ---- Découverte ----

    /** Cherche les récepteurs du réseau local pendant ~2 s ; résultats via onWifiFound. */
    public void discover() {
        new Thread(() -> {
            try (DatagramSocket s = new DatagramSocket()) {
                s.setBroadcast(true);
                s.setSoTimeout(300);
                byte[] q = {'S', 'A', 'C', '?'};
                List<InetAddress> addrs = new ArrayList<>();
                try { addrs.add(InetAddress.getByName("255.255.255.255")); } catch (Exception ignored) { }
                InetAddress b = directedBroadcast();
                if (b != null) addrs.add(b);
                long end = SystemClock.uptimeMillis() + 2200;
                long nextSend = 0;
                byte[] buf = new byte[256];
                List<String> seen = new ArrayList<>();
                while (SystemClock.uptimeMillis() < end) {
                    if (SystemClock.uptimeMillis() >= nextSend) {
                        for (InetAddress a : addrs)
                            try { s.send(new DatagramPacket(q, q.length, a, PORT)); } catch (Exception ignored) { }
                        nextSend = SystemClock.uptimeMillis() + 700;
                    }
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        s.receive(p);
                        if (p.getLength() >= 4 && buf[0] == 'S' && buf[1] == 'A' && buf[2] == 'C' && buf[3] == '!') {
                            String ip = p.getAddress().getHostAddress();
                            if (seen.contains(ip)) continue;
                            seen.add(ip);
                            String name = new String(buf, 4, p.getLength() - 4, StandardCharsets.UTF_8);
                            main.post(() -> listener.onWifiFound(name, ip));
                        }
                    } catch (SocketTimeoutException ignored) { }
                }
            } catch (Exception ignored) { }
            main.post(() -> listener.onWifiFound(null, null)); // fin de recherche
        }, "wifi-scan").start();
    }

    @SuppressWarnings("deprecation")
    private InetAddress directedBroadcast() {
        try {
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            DhcpInfo d = wm.getDhcpInfo();
            if (d == null || d.ipAddress == 0) return null;
            int bc = (d.ipAddress & d.netmask) | ~d.netmask;
            byte[] q = {(byte) bc, (byte) (bc >> 8), (byte) (bc >> 16), (byte) (bc >> 24)};
            return InetAddress.getByAddress(q);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- Divers ----

    @SuppressWarnings("deprecation")
    private void acquireLock() {
        try {
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            int type = android.os.Build.VERSION.SDK_INT >= 29
                    ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            lock = wm.createWifiLock(type, "icontroller");
            lock.setReferenceCounted(false);
            lock.acquire();
        } catch (Exception ignored) { }
    }

    private void releaseLock() {
        try { if (lock != null && lock.isHeld()) lock.release(); } catch (Exception ignored) { }
        lock = null;
    }

    private void post(boolean ok, String msg) {
        main.post(() -> listener.onWifiState(ok, msg));
    }
}
