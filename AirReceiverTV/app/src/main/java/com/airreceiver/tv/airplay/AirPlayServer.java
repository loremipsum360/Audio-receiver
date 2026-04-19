package com.airreceiver.tv.airplay;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

/**
 * Serveur AirPlay (RAOP - Remote Audio Output Protocol).
 *
 * Protocole AirPlay audio :
 *  1. mDNS/Bonjour : annonce le service "_raop._tcp" sur le réseau local
 *     → l'iPhone / Mac voit la box dans la liste AirPlay
 *  2. RTSP : négociation de session (port 5000)
 *  3. RTP  : réception des paquets audio (port 6000 UDP)
 *  4. Décodage ALAC → lecture via AudioTrack Android
 *
 * Dépendances (à ajouter dans build.gradle) :
 *   implementation 'org.jmdns:jmdns:3.5.8'        // mDNS / Bonjour
 *   implementation 'org.openalac:alac-decoder:1.0' // décodeur Apple Lossless
 */
public class AirPlayServer {

    private static final String TAG = "AirPlayServer";

    // Ports AirPlay standard
    private static final int RTSP_PORT = 5000;
    private static final int RTP_PORT  = 6000;
    private static final int RTCP_PORT = 6001;

    // Nom du service mDNS (format : MAC_ADDRESS@NomAppareil)
    private static final String MDNS_TYPE = "_raop._tcp.local.";

    private final Context  mContext;
    private final String   mDeviceName;
    private final Listener mListener;

    private JmDNS         mJmDNS;
    private ServerSocket  mRtspSocket;
    private DatagramSocket mRtpSocket;
    private AudioTrack    mAudioTrack;
    private ExecutorService mExecutor;
    private volatile boolean mRunning = false;

    // MAC address simulée (unique par installation)
    private final String mMacAddress;

    public interface Listener {
        void onClientConnected(String clientName);
        void onClientDisconnected();
        void onError(String error);
    }

    public AirPlayServer(Context context, String deviceName, Listener listener) {
        mContext    = context;
        mDeviceName = deviceName;
        mListener   = listener;
        mMacAddress = generatePseudoMac();
        mExecutor   = Executors.newCachedThreadPool();
    }

    // ─── Démarrage ───────────────────────────────────────────────────────────

    public void start() {
        mRunning = true;
        mExecutor.execute(this::registerMdns);
        mExecutor.execute(this::listenRtsp);
        mExecutor.execute(this::listenRtp);
    }

    // ─── mDNS / Bonjour (découverte par les appareils Apple) ─────────────────

    private void registerMdns() {
        try {
            InetAddress localAddr = getLocalAddress();
            mJmDNS = JmDNS.create(localAddr, mDeviceName);

            // Propriétés du service AirPlay annoncées via mDNS
            java.util.Map<String, String> props = new java.util.HashMap<>();
            props.put("txtvers", "1");
            props.put("ch",  "2");          // 2 canaux stéréo
            props.put("cn",  "0,1,2,3");    // codecs : PCM, ALAC, AAC, AAC-ELD
            props.put("da",  "true");
            props.put("et",  "0,3,5");      // chiffrement
            props.put("md",  "0,1,2");
            props.put("pw",  "false");       // pas de mot de passe
            props.put("sr",  "44100");       // sample rate
            props.put("ss",  "16");          // bits par sample
            props.put("sv",  "false");
            props.put("tp",  "UDP");
            props.put("vn",  "65537");
            props.put("vs",  "130.14");      // version AirPlay
            props.put("am",  "AppleTV3,2"); // modèle simulé

            // Nom du service : MACADDRESS@NomAppareil
            String serviceName = mMacAddress + "@" + mDeviceName;

            ServiceInfo serviceInfo = ServiceInfo.create(
                    MDNS_TYPE,
                    serviceName,
                    RTSP_PORT,
                    0, 0,
                    props
            );

            mJmDNS.registerService(serviceInfo);
            Log.i(TAG, "mDNS enregistré : " + serviceName + " sur " + localAddr.getHostAddress());

        } catch (IOException e) {
            Log.e(TAG, "Erreur mDNS", e);
            if (mListener != null) mListener.onError("Erreur réseau mDNS : " + e.getMessage());
        }
    }

    // ─── Serveur RTSP (négociation de session) ────────────────────────────────

    private void listenRtsp() {
        try {
            mRtspSocket = new ServerSocket(RTSP_PORT);
            Log.i(TAG, "RTSP en écoute sur le port " + RTSP_PORT);

            while (mRunning) {
                Socket client = mRtspSocket.accept();
                mExecutor.execute(() -> handleRtspClient(client));
            }
        } catch (IOException e) {
            if (mRunning) Log.e(TAG, "Erreur RTSP", e);
        }
    }

    private void handleRtspClient(Socket client) {
        String clientIp = client.getInetAddress().getHostAddress();
        Log.i(TAG, "Connexion RTSP depuis " + clientIp);

        try (RtspSession session = new RtspSession(client, RTP_PORT)) {
            String clientName = session.getClientName();
            if (clientName == null || clientName.isEmpty()) clientName = clientIp;

            if (mListener != null) mListener.onClientConnected(clientName);

            // Initialise AudioTrack pour la lecture
            initAudioTrack(session.getSampleRate(), session.getChannels());

            // Boucle de session RTSP
            session.handle();

        } catch (Exception e) {
            Log.e(TAG, "Erreur session RTSP", e);
        } finally {
            if (mListener != null) mListener.onClientDisconnected();
            releaseAudioTrack();
        }
    }

    // ─── Réception RTP (paquets audio UDP) ───────────────────────────────────

    private void listenRtp() {
        try {
            mRtpSocket = new DatagramSocket(RTP_PORT);
            byte[] buffer = new byte[2048];
            Log.i(TAG, "RTP en écoute sur le port " + RTP_PORT);

            while (mRunning) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                mRtpSocket.receive(packet);
                processRtpPacket(packet.getData(), packet.getLength());
            }
        } catch (IOException e) {
            if (mRunning) Log.e(TAG, "Erreur RTP", e);
        }
    }

    private void processRtpPacket(byte[] data, int length) {
        // Structure paquet RTP AirPlay :
        //  - octets 0-1  : version + flags
        //  - octets 2-3  : type payload (0x60 = ALAC)
        //  - octets 4-7  : numéro de séquence
        //  - octets 8-11 : timestamp
        //  - octets 12+  : payload audio (ALAC encodé)

        if (length < 12) return;

        byte[] payload = new byte[length - 12];
        System.arraycopy(data, 12, payload, 0, payload.length);

        // Décodage ALAC → PCM
        byte[] pcmData = AlacDecoder.decode(payload);

        // Lecture via AudioTrack Android
        if (mAudioTrack != null && pcmData != null) {
            mAudioTrack.write(pcmData, 0, pcmData.length);
        }
    }

    // ─── AudioTrack ──────────────────────────────────────────────────────────

    private void initAudioTrack(int sampleRate, int channels) {
        releaseAudioTrack();

        int channelConfig = channels == 2
                ? AudioFormat.CHANNEL_OUT_STEREO
                : AudioFormat.CHANNEL_OUT_MONO;

        int bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT
        );

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();

        AudioFormat format = new AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build();

        mAudioTrack = new AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();

        mAudioTrack.play();
        Log.i(TAG, "AudioTrack initialisé : " + sampleRate + "Hz, " + channels + "ch");
    }

    private void releaseAudioTrack() {
        if (mAudioTrack != null) {
            try {
                mAudioTrack.stop();
                mAudioTrack.release();
            } catch (Exception e) {
                Log.w(TAG, "Erreur release AudioTrack", e);
            }
            mAudioTrack = null;
        }
    }

    // ─── Utilitaires ─────────────────────────────────────────────────────────

    private InetAddress getLocalAddress() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                java.util.Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        return addr;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Impossible de déterminer l'adresse locale", e);
        }
        return InetAddress.getLoopbackAddress();
    }

    /** Génère une adresse MAC pseudo-aléatoire stable (basée sur le nom de l'appareil) */
    private String generatePseudoMac() {
        long hash = Math.abs(android.os.Build.SERIAL.hashCode());
        return String.format("00:13:%02X:%02X:%02X:%02X",
                (hash >> 24) & 0xFF, (hash >> 16) & 0xFF,
                (hash >> 8)  & 0xFF,  hash         & 0xFF);
    }

    // ─── Arrêt ───────────────────────────────────────────────────────────────

    public void stop() {
        mRunning = false;
        try {
            if (mJmDNS != null)     mJmDNS.close();
            if (mRtspSocket != null) mRtspSocket.close();
            if (mRtpSocket != null)  mRtpSocket.close();
        } catch (IOException e) {
            Log.w(TAG, "Erreur lors de l'arrêt", e);
        }
        releaseAudioTrack();
        mExecutor.shutdownNow();
        Log.i(TAG, "Serveur AirPlay arrêté");
    }
}
