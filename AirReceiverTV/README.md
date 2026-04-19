# AirReceiver TV

Application Android TV qui transforme votre box en récepteur AirPlay audio.
Diffusez de la musique depuis votre iPhone ou Mac vers votre Android TV —
sans aucune app tierce côté Apple.

---

## Fonctionnement

```
iPhone / Mac                     Android TV Box
────────────────                 ─────────────────────────────
   Musique                           AirReceiver TV
   Control Center   ──AirPlay──▶  [Service RTSP + RTP]
   "Salon TV" ✓                      ↓
                                   AudioTrack → haut-parleurs TV
```

**Protocoles utilisés :**
- **mDNS/Bonjour** : annonce la box sur le réseau (port 5353 UDP multicast)
- **RTSP** : négociation de session (port 5000 TCP)
- **RTP** : réception audio en temps réel (port 6000 UDP)
- **ALAC** : décodage Apple Lossless Audio Codec → PCM

---

## Prérequis

- Android TV avec Android 5.0+ (API 21+)
- Box et appareils Apple sur le **même réseau Wi-Fi**
- Android Studio (pour compiler) ou APK pré-compilé

---

## Installation

### Option A — Compiler depuis les sources (recommandé)

```bash
# Cloner le projet
git clone https://github.com/votre-repo/AirReceiverTV
cd AirReceiverTV

# Compiler
./gradlew assembleDebug

# Installer sur la box (ADB)
adb connect IP_DE_VOTRE_BOX:5555
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Option B — Débogage ADB sans fil (Android TV)

1. Sur la box : **Paramètres → À propos → Appuyer 7x sur "Build number"** (activer le mode développeur)
2. **Paramètres → Options développeur → Débogage ADB** → Activer
3. **Paramètres → Options développeur → Débogage ADB via réseau** → Activer
4. Sur votre Mac :
   ```bash
   brew install android-platform-tools
   adb connect 192.168.1.XX:5555   # IP de votre box
   adb install AirReceiverTV.apk
   ```

### Démarrage automatique

L'app démarre **automatiquement** au boot de la box grâce au `BootReceiver`.
Aucune configuration supplémentaire n'est nécessaire après la première installation.

Pour vérifier que l'app tourne en arrière-plan :
```bash
adb shell dumpsys activity services com.airreceiver.tv
```

---

## Utilisation

### Depuis l'iPhone
1. Lancez de la musique (Musique, Spotify, etc.)
2. Ouvrez le **Centre de contrôle** (glisser depuis le bas/haut)
3. Appuyez sur l'icône **AirPlay** (triangle avec cercles)
4. Sélectionnez **"[Votre box] AirPlay"**
5. La musique passe sur la TV 🎵

### Depuis le Mac
1. Cliquez sur l'icône **Control Center** dans la barre de menu
2. Cliquez sur **AirPlay**
3. Sélectionnez **"[Votre box] AirPlay"**

---

## Dépannage

| Problème | Solution |
|---|---|
| La box n'apparaît pas dans AirPlay | Vérifiez que les deux appareils sont sur le même Wi-Fi |
| La box n'apparaît pas dans AirPlay | Redémarrez l'app : `adb shell am force-stop com.airreceiver.tv` |
| Pas de son | Vérifiez le volume sur la box ET sur l'iPhone |
| Latence élevée | Normal pour le Wi-Fi (100-300ms). Utilisez pour la musique, pas la vidéo |
| L'app ne démarre pas au boot | Vérifiez les permissions dans Paramètres → Applications |

### Vérifier les logs AirPlay
```bash
adb logcat -s AirPlayServer:D RtspSession:D AirPlayService:D BootReceiver:I
```

---

## Architecture du projet

```
AirReceiverTV/
├── app/src/main/
│   ├── AndroidManifest.xml          # Permissions + BootReceiver + Service
│   └── java/com/airreceiver/tv/
│       ├── airplay/
│       │   ├── AirPlayServer.java   # Serveur RTSP + RTP + mDNS
│       │   ├── RtspSession.java     # Gestion protocole RTSP
│       │   └── AlacDecoder.java     # Décodage ALAC → PCM
│       ├── service/
│       │   └── AirPlayService.java  # Service foreground (persist au boot)
│       ├── receiver/
│       │   └── BootReceiver.java    # Démarrage automatique au boot
│       └── ui/
│           └── MainActivity.java   # Interface Android TV (Leanback)
└── app/build.gradle                 # Dépendances (jmdns, leanback)
```

---

## Dépendances clés

| Bibliothèque | Usage |
|---|---|
| `org.jmdns:jmdns:3.5.8` | Annonce mDNS/Bonjour sur le réseau |
| `androidx.leanback:leanback:1.2.0` | UI Android TV |
| `openalac` (optionnel) | Décodeur ALAC natif (JNI) |

---

## Ports réseau

| Port | Protocole | Usage |
|---|---|---|
| 5353 | UDP multicast | mDNS (découverte automatique) |
| 5000 | TCP | RTSP (négociation AirPlay) |
| 6000 | UDP | RTP (flux audio) |
| 6001 | UDP | RTCP (contrôle) |

Si vous avez un firewall sur la box, ouvrez ces ports.

---

## Notes techniques

- L'app utilise un **WakeLock partiel** pour empêcher le CPU de dormir
  pendant la diffusion audio.
- Le **MulticastLock Wi-Fi** est requis pour recevoir les paquets mDNS.
- Le service est déclaré `START_STICKY` : Android le redémarre automatiquement
  s'il est tué par le système.
- La box apparaît dans AirPlay sous le nom : `[Modèle de box] AirPlay`
  (ex: "SHIELD TV AirPlay").

---

## Alternatives toutes-faites

Si vous ne souhaitez pas compiler vous-même, ces apps font la même chose :

- **[AirReceiver](https://play.google.com/store/apps/details?id=com.ionizone.airreceiver)** — payante (~4€), robuste, supporte aussi Chromecast
- **[AllConnect](https://play.google.com/store/apps/details?id=com.allcast)** — gratuite avec pub

---

## Licence

MIT — libre d'utilisation et modification.
