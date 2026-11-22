# SA Controller — tableau du projet (dossier `iController`)

## État
APK Android (Java, minSdk 28) qui transforme un vieux téléphone Android 9+ en manette Bluetooth HID, reconnue nativement par une Android TV ou un Mac. La v0.3 compile (`SA-Controller-v0.3.apk`) ; l'interface façon PS3 avec touches Xbox est terminée et vérifiée dans l'émulateur (état « connecté » non vu, il faut le Bluetooth). L'écran GTA (à pied / en voiture, inclinaison, touches réassignables) est fait aussi. Reste : test réel sur la TV avec GTA San Andreas et ajustement des touches.

## Fils
| Fil | Id | Modèle | Statut | Livrable |
|---|---|---|---|---|
| SA Controller · Interface manette | `local_221c959f-00f1-400a-8602-07e92b2b676c` | Opus | fini (vérifié par le coordinateur) | code + captures dans `captures/` |
| SA Controller · Interface GTA | `local_6033f194-ac2a-41c1-9d09-06eed8a5384b` | Opus | fini (vérifié par le coordinateur) | code + captures `captures/gta-*.png` |
| SA Controller · README | `local_47b6cb92-0fc5-4aa3-9ee4-f790ffdb6b71` | Sonnet | fini (relu par le coordinateur) | doc `README.md` |
| SA Controller · Refonte UX | `local_9f02bfbc-8c01-4f6d-96a8-df4ad27a0d13` | Opus (high) | fini (vérifié par le coordinateur : diff, captures) | code + nouvelles captures `captures/` |

## À faire par Driss
- Installer `SA-Controller-v0.3.apk` sur le téléphone, le connecter à l'Android TV, tester dans GTA San Andreas et noter les touches qui ne font pas la bonne action.

## Décisions de Driss
- 2026-11-22 : téléphone sous Android 9 ou plus → Bluetooth HID natif (pas de Wi-Fi ni d'USB en v0.3).
- 2026-11-22 : licence du SDK Android acceptée ; SDK, JDK 17, Gradle et émulateur installés sur le Mac.
- 2026-11-22 : interface inspirée de la PS3, avec les touches Xbox.
- 2026-11-22 : écran dédié GTA San Andreas (modes à pied / voiture, gyroscope, touches réassignables).
- 2026-11-22 : code poussé sur GitHub `Ligrade/SA-Controller` (public, dépôt déjà créé par Driss) ; APK exclu du dépôt.
- 2026-11-22 : README en français demandé.
- 2026-11-22 : projet renommé « SA Controller » (nom affiché, APK) ; package `com.dynamicagence.icontroller` et dossier inchangés.

- 2026-11-22 : refonte UX plus moderne demandée (fil Refonte UX) ; le Wi-Fi n'est jamais touché par l'appli (Bluetooth seul).

## Règles du projet
- Premier environnement de test : Android TV + GTA San Andreas ; ensuite le MacBook.
- Ne pas changer l'ordre des boutons HID dans `HidGamepad.java` (correspondance Android/Linux vérifiée).
