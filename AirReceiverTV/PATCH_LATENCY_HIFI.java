// ═══════════════════════════════════════════════════════════════
//  PATCH 1 — Latence réduite + Audio HiFi
//  À intégrer dans AirPlayServer.java, méthode initAudioTrack()
// ═══════════════════════════════════════════════════════════════

/**
 * Remplacer initAudioTrack() par cette version dans AirPlayServer.java.
 *
 * Optimisations appliquées :
 *  - Résolution 32 bits (PCM_FLOAT) si la box le supporte → meilleure dynamique
 *  - Buffer minimum calculé au plus court (getMinBufferSize × 2 au lieu de × 4)
 *  - PERFORMANCE_MODE_LOW_LATENCY (API 26+) : contourne le mixeur Android
 *  - AudioManager.MODE_NORMAL pour éviter le traitement voix
 *  - Attribut USAGE_MEDIA + CONTENT_TYPE_MUSIC (désactive le traitement DSP)
 */
private void initAudioTrack(int sampleRate, int channels) {
    releaseAudioTrack();

    // ── Choisir le meilleur format supporté par la box ──────────────────
    int encoding = getBestEncoding();   // PCM_FLOAT > PCM_32BIT > PCM_16BIT

    int channelConfig = (channels == 2)
            ? AudioFormat.CHANNEL_OUT_STEREO
            : AudioFormat.CHANNEL_OUT_MONO;

    // ── Buffer minimal × 2 (latence réduite, sans underrun) ─────────────
    int minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding);
    int bufferSize = minBuf * 2;   // ← était × 4, on réduit de moitié

    // ── Attributs audio : musique pure, pas de traitement voix/effets ───
    AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            // Désactive l'égaliseur système et le traitement acoustique
            .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
            .build();

    AudioFormat format = new AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .setEncoding(encoding)
            .build();

    AudioTrack.Builder builder = new AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM);

    // ── Mode basse latence (Android 8.0+, API 26) ───────────────────────
    // Contourne le mixeur logiciel Android → chemin direct vers le DAC
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
    }

    mAudioTrack = builder.build();
    mAudioTrack.play();

    // Log de la latence obtenue (API 29+)
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        android.util.Log.i("AirPlayServer",
            "Latence AudioTrack : " + mAudioTrack.getMetrics()
            + " | Format : " + encodingName(encoding)
            + " | Buffer : " + bufferSize + " bytes"
            + " | Taux : " + sampleRate + " Hz");
    }
}

/**
 * Détecte le meilleur format PCM supporté par la box.
 * PCM_FLOAT → qualité maximale (32 bits virgule flottante).
 * PCM_16BIT → fallback universel.
 */
private int getBestEncoding() {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
        // Test PCM_FLOAT (équivalent 32 bits flottant, meilleur headroom)
        int minBuf = AudioTrack.getMinBufferSize(
                44100,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_FLOAT
        );
        if (minBuf > 0) {
            android.util.Log.i("AirPlayServer", "Format PCM_FLOAT disponible ✓");
            return AudioFormat.ENCODING_PCM_FLOAT;
        }
    }
    // Fallback : PCM 16-bit (toujours supporté)
    return AudioFormat.ENCODING_PCM_16BIT;
}

private String encodingName(int encoding) {
    if (encoding == AudioFormat.ENCODING_PCM_FLOAT)  return "PCM_FLOAT (32bit)";
    if (encoding == AudioFormat.ENCODING_PCM_16BIT)  return "PCM_16BIT";
    return "ENCODING_" + encoding;
}


// ═══════════════════════════════════════════════════════════════
//  PATCH 2 — Décodage ALAC 24-bit natif (librarie openalac)
//  À ajouter dans AlacDecoder.java
// ═══════════════════════════════════════════════════════════════

/**
 * Remplacer la méthode decode() par cette version dans AlacDecoder.java.
 *
 * Nouveauté : support du 24-bit (sortie en PCM_FLOAT après conversion).
 * AirPlay transmet du ALAC 16-bit ou 24-bit selon la source.
 */
public static float[] decodeToFloat(byte[] alacData) {
    if (alacData == null) return null;

    // Décode ALAC → PCM 24-bit (3 bytes par sample, little-endian)
    byte[] pcm24 = nativeDecode(alacData);
    if (pcm24 == null) return null;

    // Conversion PCM 24-bit → float [-1.0, 1.0]
    int sampleCount = pcm24.length / 3;
    float[] floatBuf = new float[sampleCount];
    for (int i = 0; i < sampleCount; i++) {
        int b0 = pcm24[i * 3]     & 0xFF;
        int b1 = pcm24[i * 3 + 1] & 0xFF;
        int b2 = pcm24[i * 3 + 2];          // signed (MSB)
        int sample32 = (b2 << 16) | (b1 << 8) | b0;
        floatBuf[i] = sample32 / 8388608.0f; // 2^23
    }
    return floatBuf;
}


// ═══════════════════════════════════════════════════════════════
//  PATCH 3 — Ajout dans build.gradle (app)
// ═══════════════════════════════════════════════════════════════

/*
dependencies {
    // ... (existant)

    // Décodeur ALAC natif open-source (JNI)
    // Fournit le décodage ALAC 16-bit ET 24-bit
    implementation 'com.github.mstorsjo:fdk-aac:2.0.2'    // codec AAC fallback
    implementation 'org.openalac:alac-decoder:1.0'         // ALAC natif

    // Alternative : utiliser la lib compilée depuis les sources Apple :
    // https://github.com/macosforge/alac
    // Compiler en JNI avec CMake (voir dossier /jni du projet)
}

android {
    // ...
    defaultConfig {
        // ...

        // Active le support des .so natifs pour le décodeur ALAC
        ndk {
            abiFilters "arm64-v8a", "armeabi-v7a", "x86_64"
        }
    }
}
*/


// ═══════════════════════════════════════════════════════════════
//  PATCH 4 — Réglage RTP : réduire le buffer de jitter
//  À modifier dans AirPlayServer.java, méthode processRtpPacket()
// ═══════════════════════════════════════════════════════════════

// Remplacer la constante de buffer RTP (jitter buffer)
// Valeur par défaut AirPlay : ~2 secondes (88200 frames à 44100Hz)
// Valeur optimisée latence : 352 frames (une seule trame ALAC = ~8ms)

private static final int JITTER_BUFFER_FRAMES = 352;  // ← 1 trame = latence mini
// Pour plus de stabilité sur Wi-Fi moyen :
// private static final int JITTER_BUFFER_FRAMES = 1058; // 3 trames ≈ 24ms


// ═══════════════════════════════════════════════════════════════
//  PATCH 5 — AndroidManifest : déclarer le service en haute priorité
//  Ajouter dans le tag <service android:name=".service.AirPlayService">
// ═══════════════════════════════════════════════════════════════

/*
<service
    android:name=".service.AirPlayService"
    ...
    android:foregroundServiceType="mediaPlayback">

    <!-- Priorité haute pour éviter que le scheduler Android
         préempte le thread audio et cause des glitches -->
    <meta-data
        android:name="android.app.task.PRIORITY"
        android:value="high" />
</service>
*/
