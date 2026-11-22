# SA Controller

Transforme un vieux téléphone Android en manette Bluetooth reconnue nativement par une Android TV ou un Mac, sans root et sans rien installer sur la TV.

<p align="center">
  <img src="captures/manette-16-9-repos.png" alt="Manette classique" width="32%">
  <img src="captures/gta-a-pied-16-9.png" alt="Écran GTA San Andreas, à pied" width="32%">
  <img src="captures/gta-en-voiture-16-9.png" alt="Écran GTA San Andreas, en voiture" width="32%">
</p>

> Version 0.4. L'appli n'a pas encore été testée sur un vrai appareil : voir [Limites](#compatibilité-et-limites).

## Fonctionnalités

**Manette classique**
Une manette tactile façon DualShock 3 (corps noir, sticks symétriques en bas, croix en quatre flèches, voyants 1 à 4) avec la disposition et les couleurs de touches Xbox : A B X Y, LB / RB, LT / RT, View, Menu, un bouton Home, clics de sticks (LS / RS), deux sticks analogiques.

**Écran GTA San Andreas**
- Deux modes, **À pied** et **En voiture**, avec chacun ses boutons nommés par action (Tirer, Viser, Sauter, Sprint, Entrer… ; Accélérer, Freiner, Frein à main, Klaxon, Radio…).
- Un stick de déplacement à gauche et une zone caméra au centre (on glisse le doigt pour regarder autour).
- En voiture, **conduite par inclinaison** : le téléphone sert de volant, avec un bouton « Recentrer » pour redéfinir la position neutre. L'angle de braquage à fond se règle (20°, 30° ou 45°).
- **Touches réassignables** : l'écran « Touches » permet d'attribuer à chaque action n'importe quelle touche de manette (ou aucune). Les réglages sont enregistrés sur le téléphone, avec un bouton pour revenir aux valeurs par défaut.
- Option pour changer de mode automatiquement quand on appuie sur Entrer / Sortir.

**Écran de connexion**
Recherche des appareils Bluetooth à proximité, association, connexion, déconnexion, et bouton « Rendre visible » pour que le téléphone puisse être associé depuis la TV ou le Mac. L'état de la connexion est affiché en clair.

## Compatibilité et limites

- **Android 9 minimum** (API 28) : l'appli utilise l'API `BluetoothHidDevice`, qui n'existe pas avant.
- **Certains fabricants désactivent ce mode HID** sur leurs téléphones. L'appli le détecte et l'indique à l'écran.
- **Android TV** : la manette est vue comme une manette Bluetooth standard.
- **Mac** : les applis qui lisent les manettes génériques (Chrome avec la Gamepad API, Steam / SDL, émulateurs) devraient la voir. En revanche, les jeux qui n'acceptent que les manettes Apple, Xbox ou PlayStation ne la reconnaîtront pas forcément.
- **Touches GTA par défaut = suppositions.** Elles sont calquées sur la disposition manette Xbox 360 de GTA San Andreas et n'ont pas été vérifiées dans le jeu. À ajuster dans l'écran « Touches ».
- **Bluetooth uniquement** : pas de Wi-Fi ni d'USB (il faudrait une appli réceptrice côté TV, ou le root).
- **Pas encore testé sur un vrai appareil** (v0.4).

## Installation

L'APK est compilé automatiquement par GitHub (onglet **Releases** du dépôt, ou **Actions** › dernier run › Artifacts). Tu peux aussi le compiler toi-même ([voir plus bas](#compiler)).

1. Télécharge l'APK depuis la dernière release et copie-le sur le téléphone.
2. Autorise l'installation depuis des sources inconnues quand Android le demande.
3. Si Play Protect avertit (l'APK est signé avec une clé de test), choisis « Installer quand même ».
4. Au premier lancement, accepte les autorisations Bluetooth (« Appareils à proximité » sur Android 12 et plus ; la localisation sur Android 9 à 11, nécessaire à la recherche d'appareils).

## Connexion

**À une Android TV**
1. Sur la TV, ouvre les réglages Bluetooth (ajouter un accessoire).
2. Dans l'appli, touche **Chercher**, puis touche le nom de la TV.
3. Accepte le code d'association sur les deux écrans.
4. Une fois « Connecté », choisis **Manette** ou **GTA San Andreas**.

Variante : touche **Rendre visible**, puis associe le téléphone depuis la TV.

**À un Mac**
1. Ouvre Réglages Système › Bluetooth sur le Mac.
2. Dans l'appli, touche **Chercher**, puis touche le nom du Mac, et accepte le code. Sinon, touche **Rendre visible** et associe le téléphone depuis la liste du Mac.
3. Une fois « Connecté », choisis un écran de manette.

Le bouton **Déconnecter** coupe la liaison. Android retire l'enregistrement HID quand l'appli passe en arrière-plan : elle se réactive au retour au premier plan.

## Correspondance des touches HID

Pour les curieux : la manette se déclare comme « Game Pad » HID (rapport n° 1) avec 16 boutons, un hat, 4 axes et 2 gâchettes analogiques. L'ordre des boutons saute volontairement les boutons HID 3 et 6, que Linux / Android attribuent à C et Z, afin que les noms tombent juste.

| Touche affichée | Bouton HID | Remarque |
|---|---|---|
| A | 1 | |
| B | 2 | |
| X | 4 | |
| Y | 5 | |
| LB | 7 | |
| RB | 8 | |
| LT | 9 | envoie aussi l'axe Brake à 255 |
| RT | 10 | envoie aussi l'axe Accelerator à 255 |
| View | 11 | Select |
| Menu | 12 | Start |
| Home | 13 | Mode |
| LS | 14 | clic du stick gauche |
| RS | 15 | clic du stick droit |
| Croix | hat 0 à 7 | 8 = relâché |
| Stick gauche | axes X / Y | −127 à 127 |
| Stick droit | axes Z / Rz | −127 à 127 |
| Gâchettes | Accelerator (RT) / Brake (LT) | 0 à 255 |

## Compiler

Prérequis : JDK 17 et le SDK Android 34. Gradle 8.11.1 est fourni par le wrapper ; le plugin Android est en 8.7.3.

1. Crée un fichier `local.properties` à la racine (il n'est pas versionné) avec le chemin du SDK :

   ```properties
   sdk.dir=/chemin/vers/Android/sdk
   ```

2. Lance la compilation :

   ```bash
   ./gradlew assembleRelease
   ```

3. L'APK se trouve dans `app/build/outputs/apk/release/app-release.apk`.

## Structure du projet

Package `com.dynamicagence.icontroller`, dans `app/src/main/java/…` :

| Fichier | Rôle |
|---|---|
| `MainActivity.java` | Écran de connexion, permissions Bluetooth, recherche et association, bascule entre les écrans |
| `HidGamepad.java` | Enregistre le téléphone comme manette Bluetooth HID, décrit le rapport et envoie l'état des boutons et des axes |
| `GamepadView.java` | Manette classique tactile (boutons, croix, sticks) |
| `GtaView.java` | Écran GTA San Andreas : modes à pied / en voiture, caméra, conduite par inclinaison |
| `GtaKeys.java` | Liste des actions GTA, touche par défaut de chacune, enregistrement des réglages |
| `GtaSettingsView.java` | Écran « Touches » : réassignation des touches et options GTA |

## Version

v0.4 (versionCode 5), `minSdk 28`, `targetSdk 34`.

Réalisation : By Ligrade
