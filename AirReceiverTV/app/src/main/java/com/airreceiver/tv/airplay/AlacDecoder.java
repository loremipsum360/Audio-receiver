package com.airreceiver.tv.airplay;

import android.util.Log;

/**
 * Wrapper pour le décodage ALAC (Apple Lossless Audio Codec).
 *
 * AirPlay transmet l'audio en ALAC. Ce wrapper utilise la bibliothèque
 * native openalac via JNI pour décoder les trames en PCM 16-bit.
 *
 * Dépendance dans build.gradle :
 *   implementation 'org.openalac:alac-decoder:1.0'
 *
 * Pour les tests sans la lib native, un mode "passthrough PCM" est disponible.
 */
public class AlacDecoder {

    private static final String TAG = "AlacDecoder";
    private static boolean sNativeAvailable = false;

    static {
        try {
            System.loadLibrary("openalac");
            sNativeAvailable = true;
            Log.i(TAG, "Décodeur ALAC natif chargé");
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "Décodeur ALAC natif non disponible, mode PCM direct");
        }
    }

    /**
     * Décode un paquet ALAC en PCM 16-bit.
     *
     * @param alacData données brutes ALAC du paquet RTP
     * @return données PCM 16-bit (little-endian, interleaved si stéréo)
     */
    public static byte[] decode(byte[] alacData) {
        if (alacData == null || alacData.length == 0) return null;

        if (sNativeAvailable) {
            return nativeDecode(alacData);
        } else {
            // Fallback : renvoie les données telles quelles
            // (ne fonctionne que si l'émetteur envoie du PCM non compressé)
            return alacData;
        }
    }

    /**
     * Initialise le décodeur avec les paramètres du flux ALAC extraits du SDP.
     *
     * @param sampleRate   fréquence d'échantillonnage (ex: 44100)
     * @param channels     nombre de canaux (1 ou 2)
     * @param bitDepth     bits par sample (ex: 16)
     * @param frameLength  longueur de trame ALAC (ex: 352)
     */
    public static void configure(int sampleRate, int channels, int bitDepth, int frameLength) {
        if (sNativeAvailable) {
            nativeConfigure(sampleRate, channels, bitDepth, frameLength);
        }
        Log.d(TAG, "Décodeur configuré : " + sampleRate + "Hz, " + channels + "ch, " + bitDepth + "bit");
    }

    // ─── Méthodes natives (JNI via libopenalac) ───────────────────────────────

    private static native byte[] nativeDecode(byte[] alacData);
    private static native void   nativeConfigure(int sampleRate, int channels, int bitDepth, int frameLength);
}
