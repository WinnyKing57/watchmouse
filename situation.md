# Situation WatchMouse

## Objectif
Contrôler la souris du PC (et à terme les gestes tactiles d'une tablette/téléphone Android) depuis la montre Wate OS.

## Architecture actuelle
### Côté montre (app Android, Wear OS)
- `welove` : app **WatchMouse** — UI souris / clavier / pavé tactile / gestes déjà développée.
- Envoi des interactions par **Bluetooth HID Device** (`BluetoothHidDevice`) : la montre se déclare en clavier/souris sans-fil auprès d'un hôte (PC / téléphone / tablette).
- Mise à jour de l'app par **OTA** (release GitHub + `ApkInstaller`) et par adb pendant le développement.
- Envoi de **rapports techniques** (« Envoyer un rapport ») compilant logcat + état Bluetooth + infos appareil.

### Serveur de rapports (VPS / Coolify)
- Récepteur HTTP (Python + SQLite) déployé sur VPS via **Coolify**, URL : `https://watch.mouse.winnyking.cloud/`.
- `POST /log` (ouvert, rate-limite) reçoit les rapports de la montre.
- Administration (liste / détail / suppression) protégée par **Basic Auth** (variables d'environnement).
- Le serveur reçoit et conserve correctement les rapports (validé avec des rapports réels de la montre).

### Dépôt
- **GitHub** : `WinnyKing57/watchmouse` (public par choix — nécessite de ne JAMAIS committer de secret).
- CI : build de l'APK de release ; protection de branche `master` (status check, PR) avec push direct autorisé pour le propriétaire.

## Ce qui fonctionne
- App compilée et signée (keystore production), installée sur la montre (adB ou OTA).
- Envoi de rapports techniques complet jusqu'au serveur (visible dans l'administration).
- Mise à jour OTA réparée (permission « Installer des apps inconnues » gérée).
- Navigation / retour dans l'app corrigée.
- Icône applicative intégrée (depuis `watchmouse_ico2.ico`).

## Bloqué : Bluetooth HID sur cette montre
- `registerApp()` retourne `true` mais le callback `onAppStatusChanged(true)` n'est **jamais émis** par le stack.
- `connect()` vers tout hôte HID (PC Debian testé, téléphone Android testé) retourne **toujours `false`**.
- Contournements testés sans effet : ré-enregistrement (3 cycles), relances de `connect()` (4 essais), app au premier plan.
- Diagnostic complet publié : **issue #3** (https://github.com/WinnyKing57/watchmouse/issues/3).
- À confirmer avec une app de référence (Kontroller / BluetoothHidDemo) : si l'échec persiste, la conclusion est un bug du HAL HID Device de la ROM OPPO/OnePlus.

## Direction retenue (en cours de conception)
### Contrôle via réseau local (contournement du bug HID)
```
Montre (WatchMouse) ──TCP/Wi-Fi──► Cible :
  ├─ « PC »      → serveur PC (uinput sur Debian / SendInput sur Windows) bouge le vrai pointeur.
  └─ « Android » → app compagnon sur la tablette/téléphone (AccessibilityService) → tap/glissé.
```
- L'UI de la montre est réutilisée telle quelle ; seul le transport change (deltas + boutons sur un protocole TCP générique).
- Pièces prévues :
  1. ✅ Client TCP dans l'app de la montre (à côté du transport HID existant) — v1.36.
  2. ✅ Serveur `uinput` Linux installé sur WinnyDebas (Debian) — `host/linux/`.
  3. ✅ App compagnon Android (récepteur + « Choix de la cible » + service d'accessibilité) — `companion/`.
  4. ⚠️ Serveur `SendInput` Windows — `host/windows/`, **écrit mais non testé** (aucune machine Windows).
- L'installation/MAJ de l'app de la montre reste via adb / notre flux OTA (pas d'API publique pour pousser un APK montre depuis un téléphone).

## Transport réseau (v1.36+)
- Protocole : JSON newline-delimited sur TCP port 8888 (détail complet : `host/README.md`).
- Montre : client TCP avec reconnexion auto (2 → 30 s), détection de liaison morte (ping hôte toutes les 5 s + timeout lecture 20 s).
- Cibles :
  - `host/linux/` : serveur uinput (souris + clavier réels) — testé unitairement (mock uinput), à valider sur WinnyDebas.
  - `host/windows/` : serveur SendInput (scancodes Set-1) — non testé.
  - `companion/` : app Android Accessibilité (tap/glissé/long-appui/molette/back sur écran tactile ; clavier non injecté au niveau texte, limité à Ctrl/Shift/Alt/entrée/flèches discrets).
- CI GitHub : build de l'app (werk `app`) + build du companion (werk `companion`, upload APK en artefact). Installation du companion sur tablette/téléphone via l'artefact CI (ou adb).

## État des versions
| Version | Statut |
|---|---|
| v1.28 | Release GitHub (correctifs scan/install) |
| v1.29 | Plus récente release GitHub publiée ; watch installée via adb |
| v1.30 → v1.31 | Installées via adb, non publiées en release |
| v1.32 → v1.35 | Instrumentation/diagnostics HID + divers fixes, installées via adb, non publiées |

À remettre à jour côté OTA : publier une release récente quand on aura stabilisé le transport réseau.

## Points d'attention / rappels
- **Keystore production** (`keystore/watchmouse.jks`) : sauvegarde impérative pour continuer les builds signés et l'OTA.
- **Token GitHub fine-grained** encore embarqué dans l'APK de la release publique **v1.28** : **à révoquer** dans GitHub (il n'est plus utilisé dans les versions suivantes).
- Ne jamais committer de secrets (`secrets.properties` est ignoré, aucune identifiant serveur en clair).