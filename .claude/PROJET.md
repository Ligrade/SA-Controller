# SA Controller — tableau du projet (dossier `iController`)

## État
APK Android (Java, minSdk 28) qui transforme un vieux téléphone Android 9+ en manette Bluetooth HID, reconnue nativement par une Android TV ou un Mac. La v0.1 compile (`SA-Controller-v0.1.apk`) ; l'interface façon PS3 avec touches Xbox est terminée et vérifiée dans l'émulateur (état « connecté » non vu, il faut le Bluetooth). L'écran GTA (à pied / en voiture, inclinaison, touches réassignables) est fait aussi. Reste : test réel sur la TV avec GTA San Andreas et ajustement des touches.

## Fils
| Fil | Id | Modèle | Statut | Livrable |
|---|---|---|---|---|
| SA Controller · Interface manette | `local_221c959f-00f1-400a-8602-07e92b2b676c` | Opus | fini (vérifié par le coordinateur) | code + captures dans `captures/` |
| SA Controller · Interface GTA | `local_6033f194-ac2a-41c1-9d09-06eed8a5384b` | Opus | fini (vérifié par le coordinateur) | code + captures `captures/gta-*.png` |

## À faire par Driss
- Installer `SA-Controller-v0.1.apk` sur le téléphone, le connecter à l'Android TV, tester dans GTA San Andreas et noter les touches qui ne font pas la bonne action.

## Décisions de Driss
- 2026-11-22 : téléphone sous Android 9 ou plus → Bluetooth HID natif (pas de Wi-Fi ni d'USB en v0.1).
- 2026-11-22 : licence du SDK Android acceptée ; SDK, JDK 17, Gradle et émulateur installés sur le Mac.
- 2026-11-22 : interface inspirée de la PS3, avec les touches Xbox.
- 2026-11-22 : écran dédié GTA San Andreas (modes à pied / voiture, gyroscope, touches réassignables).
- 2026-11-22 : projet renommé « SA Controller » (nom affiché, APK) ; package `com.dynamicagence.icontroller` et dossier inchangés.

## Règles du projet
- Premier environnement de test : Android TV + GTA San Andreas ; ensuite le MacBook.
- Ne pas changer l'ordre des boutons HID dans `HidGamepad.java` (correspondance Android/Linux vérifiée).
