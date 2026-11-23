#!/usr/bin/env python3
"""
SA Controller : récepteur Mac (Wi-Fi).

Reçoit les paquets UDP de l'appli SA Controller (mode Wi-Fi) et les transforme en
clavier + souris sur le Mac (la manette HID virtuelle n'existe pas sur macOS sans pilote signé).
Python 3 seul, aucune installation : python3 sa-recepteur.py

  --test      n'injecte rien, affiche seulement ce qui serait envoyé (pour vérifier la liaison)
  --port N    port UDP (38492 par défaut, le même que dans l'appli)
  --souris N  vitesse de la caméra (stick droit -> souris), 1.0 par défaut

Protocole (v1) : voir WifiLink.java. Paquet d'état de 16 octets :
  'SAC' 1 | seq u16 | mode (0 à pied, 1 voiture) | 9 octets de rapport HID :
  boutons (2 octets), croix, X, Y, Z (stick droit X), Rz (stick droit Y), gaz, frein.
"""
import argparse
import ctypes
import ctypes.util
import socket
import struct
import sys
import threading
import time

PORT = 38492
TIMEOUT_S = 1.0   # silence du téléphone : tout est relâché

# ---- Boutons HID (index de bit, comme HidGamepad.java) ----
A, B, X, Y, L1, R1, L2, R2, SELECT, START, MODE, L3, R3 = 0, 1, 3, 4, 6, 7, 8, 9, 10, 11, 12, 13, 14
HAT_UP, HAT_RIGHT, HAT_DOWN, HAT_LEFT = "haut", "droite", "bas", "gauche"

# ---- Touches Mac (codes virtuels ANSI) ----
KEY = dict(a=0, s=1, d=2, f=3, h=4, g=5, z=6, x=7, c=8, v=9, b=11, q=12, w=13, e=14, r=15, y=16, t=17,
           o=31, u=32, i=34, p=35, l=37, j=38, k=40, n=45, m=46,
           retour=36, tab=48, espace=49, effacer=51, echap=53, maj=56, ctrl=59, alt=58,
           haut=126, bas=125, gauche=123, droite=124)
MOUSE_L, MOUSE_R = "clic-gauche", "clic-droit"

# ---- Correspondance : bouton de la manette -> touche, par mode ----
# Calquée sur les touches clavier PC de GTA San Andreas. À ADAPTER si le jeu sur Mac diffère.
FOOT = {
    R2: MOUSE_L,        # tirer / frapper
    L2: MOUSE_R,        # viser
    X: "maj",           # sauter
    A: "espace",        # sprint
    Y: "f",             # entrer dans un véhicule
    B: "retour",        # action
    L3: "c",            # s'accroupir
    L1: "q", R1: "e",   # arme précédente / suivante
    R3: "b",            # regarder derrière
    SELECT: "v",        # changer de vue
    START: "echap",     # carte / pause
    HAT_LEFT: "n", HAT_RIGHT: "y",  # non / oui
}
CAR = {
    R2: "w",            # accélérer
    L2: "s",            # freiner / reculer
    R1: "espace",       # frein à main
    B: "ctrl",          # tirer
    Y: "f",             # sortir
    L3: "h",            # klaxon
    HAT_LEFT: "q", HAT_RIGHT: "e",  # radio
    R3: "2",            # mission annexe
    SELECT: "v",
    START: "echap",
}
KEY["2"] = 19
# Sticks gauche -> clavier (seuil de déclenchement). Direction en voiture : A / D seulement.
STICK_THRESHOLD = 0.35


class Output:
    """Injection d'événements. En --test, affiche au lieu d'injecter."""

    def __init__(self, dry):
        self.dry = dry
        self.keys = set()
        self.mouse = set()
        if dry:
            return
        if sys.platform != "darwin":
            sys.exit("L'injection n'est prévue que pour macOS. Utilise --test ailleurs.")
        lib = ctypes.CDLL(ctypes.util.find_library("ApplicationServices"))
        self.lib = lib

        class CGPoint(ctypes.Structure):
            _fields_ = [("x", ctypes.c_double), ("y", ctypes.c_double)]
        self.CGPoint = CGPoint
        lib.CGEventCreateKeyboardEvent.restype = ctypes.c_void_p
        lib.CGEventCreateKeyboardEvent.argtypes = [ctypes.c_void_p, ctypes.c_uint16, ctypes.c_bool]
        lib.CGEventCreateMouseEvent.restype = ctypes.c_void_p
        lib.CGEventCreateMouseEvent.argtypes = [ctypes.c_void_p, ctypes.c_uint32, CGPoint, ctypes.c_uint32]
        lib.CGEventCreate.restype = ctypes.c_void_p
        lib.CGEventCreate.argtypes = [ctypes.c_void_p]
        lib.CGEventGetLocation.restype = CGPoint
        lib.CGEventGetLocation.argtypes = [ctypes.c_void_p]
        lib.CGEventPost.argtypes = [ctypes.c_uint32, ctypes.c_void_p]
        lib.CGEventSetIntegerValueField.argtypes = [ctypes.c_void_p, ctypes.c_uint32, ctypes.c_int64]
        lib.CFRelease.argtypes = [ctypes.c_void_p]
        lib.AXIsProcessTrusted.restype = ctypes.c_bool
        if not lib.AXIsProcessTrusted():
            print("⚠ Accessibilité non autorisée : Réglages Système › Confidentialité et sécurité › "
                  "Accessibilité, ajoute le Terminal (ou l'appli qui lance ce script), puis relance.")

    def _post(self, ev):
        self.lib.CGEventPost(0, ev)   # kCGHIDEventTap
        self.lib.CFRelease(ev)

    def key(self, name, down):
        if down:
            self.keys.add(name)
        else:
            self.keys.discard(name)
        if self.dry:
            print(("  touche  ↓ " if down else "  touche  ↑ ") + name)
            return
        self._post(self.lib.CGEventCreateKeyboardEvent(None, KEY[name], down))

    def button(self, name, down):
        if name in (MOUSE_L, MOUSE_R):
            if down:
                self.mouse.add(name)
            else:
                self.mouse.discard(name)
            if self.dry:
                print(("  souris  ↓ " if down else "  souris  ↑ ") + name)
                return
            left = name == MOUSE_L
            typ = (1 if down else 2) if left else (3 if down else 4)
            pos = self._pos()
            self._post(self.lib.CGEventCreateMouseEvent(None, typ, pos, 0 if left else 1))
        else:
            self.key(name, down)

    def _pos(self):
        e = self.lib.CGEventCreate(None)
        p = self.lib.CGEventGetLocation(e)
        self.lib.CFRelease(e)
        return p

    def move(self, dx, dy):
        if self.dry:
            return
        p = self._pos()
        p = self.CGPoint(p.x + dx, p.y + dy)
        typ = 6 if MOUSE_L in self.mouse else 7 if MOUSE_R in self.mouse else 5  # dragged / moved
        ev = self.lib.CGEventCreateMouseEvent(None, typ, p, 0)
        self.lib.CGEventSetIntegerValueField(ev, 4, int(dx))   # kCGMouseEventDeltaX
        self.lib.CGEventSetIntegerValueField(ev, 5, int(dy))   # kCGMouseEventDeltaY
        self._post(ev)

    def release_all(self):
        for k in list(self.keys):
            self.key(k, False)
        for m in list(self.mouse):
            self.button(m, False)


class Receiver:
    def __init__(self, out, mouse_speed, name):
        self.out = out
        self.speed = mouse_speed
        self.name = name
        self.last_seq = None
        self.last_rx = 0.0
        self.active = False
        self.held = set()        # touches/boutons actuellement tenus
        self.rx = self.ry = 0.0  # stick droit -> souris
        self.lock = threading.Lock()

    def wanted(self, mode, report):
        buttons = report[0] | (report[1] << 8)
        hat = report[2] & 0x0F
        lx = struct.unpack("b", bytes([report[3]]))[0] / 127.0
        ly = struct.unpack("b", bytes([report[4]]))[0] / 127.0
        rx = struct.unpack("b", bytes([report[5]]))[0] / 127.0
        ry = struct.unpack("b", bytes([report[6]]))[0] / 127.0
        gas, brake = report[7], report[8]
        table = CAR if mode == 1 else FOOT
        want = set()
        for bit, target in table.items():
            if isinstance(bit, int) and buttons & (1 << bit):
                want.add(target)
        hats = {0: [HAT_UP], 1: [HAT_UP, HAT_RIGHT], 2: [HAT_RIGHT], 3: [HAT_DOWN, HAT_RIGHT],
                4: [HAT_DOWN], 5: [HAT_DOWN, HAT_LEFT], 6: [HAT_LEFT], 7: [HAT_UP, HAT_LEFT]}.get(hat, [])
        for h in hats:
            if h in table:
                want.add(table[h])
        # Gâchettes analogiques : si le bouton n'est pas pressé mais l'axe l'est (clients futurs)
        if gas > 128 and R2 in table:
            want.add(table[R2])
        if brake > 128 and L2 in table:
            want.add(table[L2])
        # Stick gauche : à pied = ZQSD/WASD (marche), en voiture = direction A / D
        if lx < -STICK_THRESHOLD:
            want.add("a")
        if lx > STICK_THRESHOLD:
            want.add("d")
        if mode == 0:
            if ly < -STICK_THRESHOLD:
                want.add("w")
            if ly > STICK_THRESHOLD:
                want.add("s")
        return want, rx, ry

    def handle_state(self, data):
        seq = (data[4] << 8) | data[5]
        if self.last_seq is not None and ((seq - self.last_seq) & 0xFFFF) > 0x8000:
            return  # paquet en retard : ignoré
        self.last_seq = seq
        mode = data[6]
        want, rx, ry = self.wanted(mode, data[7:16])
        with self.lock:
            self.last_rx = time.monotonic()
            if not self.active:
                self.active = True
                print("● manette connectée")
            for t in list(self.held - want):
                self.out.button(t, False)
                self.held.discard(t)
            for t in want - self.held:
                self.out.button(t, True)
                self.held.add(t)
            self.rx, self.ry = rx, ry

    def pump(self):
        """Souris (stick droit) à ~120 Hz et coupure sur silence."""
        while True:
            time.sleep(1 / 120)
            with self.lock:
                if self.active and time.monotonic() - self.last_rx > TIMEOUT_S:
                    self.active = False
                    self.out.release_all()
                    self.held.clear()
                    self.rx = self.ry = 0
                    print("○ manette silencieuse : tout est relâché")
                if self.active and (abs(self.rx) > 0.08 or abs(self.ry) > 0.08):
                    k = 14 * self.speed
                    self.out.move(self.rx * abs(self.rx) * k, self.ry * abs(self.ry) * k)


def local_ips():
    ips = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    return sorted(ips)


def main():
    ap = argparse.ArgumentParser(description="Récepteur Wi-Fi de SA Controller pour Mac")
    ap.add_argument("--test", action="store_true", help="n'injecte rien, affiche les événements")
    ap.add_argument("--port", type=int, default=PORT)
    ap.add_argument("--souris", type=float, default=1.0)
    args = ap.parse_args()

    name = socket.gethostname().split(".")[0]
    out = Output(args.test)
    rcv = Receiver(out, args.souris, name)
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("0.0.0.0", args.port))
    print(f"SA Controller · récepteur « {name} » en écoute sur le port UDP {args.port}"
          + (" (mode test : rien n'est injecté)" if args.test else ""))
    for ip in local_ips():
        print(f"  Adresse à saisir dans l'appli : {ip}")
    print("Ctrl+C pour arrêter.\n")
    threading.Thread(target=rcv.pump, daemon=True).start()
    try:
        while True:
            data, addr = sock.recvfrom(256)
            if len(data) >= 4 and data[:3] == b"SAC":
                if data[3:4] == b"?":
                    sock.sendto(b"SAC!" + name.encode("utf-8"), addr)
                elif data[3] == 1 and len(data) >= 16:
                    rcv.handle_state(data)
                    sock.sendto(b"SACA" + name.encode("utf-8"), addr)
    except KeyboardInterrupt:
        pass
    finally:
        out.release_all()
        print("\nArrêté, touches relâchées.")


if __name__ == "__main__":
    main()
