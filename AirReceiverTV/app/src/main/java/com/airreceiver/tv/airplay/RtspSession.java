package com.airreceiver.tv.airplay;

import android.util.Log;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

/**
 * Gère une session RTSP pour le protocole AirPlay (RAOP).
 *
 * Les méthodes RTSP utilisées par AirPlay :
 *  - OPTIONS    : découverte des capacités
 *  - ANNOUNCE   : description du flux (SDP) — sampleRate, channels, codec
 *  - SETUP      : négociation des ports RTP/RTCP
 *  - RECORD     : début de la diffusion
 *  - SET_PARAMETER : contrôle du volume
 *  - TEARDOWN   : fin de session
 */
public class RtspSession implements Closeable {

    private static final String TAG = "RtspSession";

    private final Socket mSocket;
    private final int    mRtpPort;
    private final BufferedReader mReader;
    private final PrintWriter    mWriter;

    private String mClientName = "";
    private int    mSampleRate  = 44100;
    private int    mChannels    = 2;
    private String mSessionId   = "1";
    private volatile boolean mActive = true;

    public RtspSession(Socket socket, int rtpPort) throws IOException {
        mSocket  = socket;
        mRtpPort = rtpPort;
        mReader  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        mWriter  = new PrintWriter(socket.getOutputStream(), true);
    }

    /** Boucle principale : lit et traite les requêtes RTSP du client Apple */
    public void handle() {
        while (mActive && !mSocket.isClosed()) {
            try {
                RtspRequest request = readRequest();
                if (request == null) break;

                Log.d(TAG, "RTSP → " + request.method + " " + request.uri);
                handleRequest(request);

            } catch (IOException e) {
                if (mActive) Log.w(TAG, "Connexion RTSP fermée : " + e.getMessage());
                break;
            }
        }
    }

    private void handleRequest(RtspRequest req) throws IOException {
        switch (req.method) {

            case "OPTIONS":
                // Liste les méthodes supportées
                sendResponse(req, 200, "OK", Map.of(
                        "Public", "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER"
                ), null);
                break;

            case "ANNOUNCE":
                // Parsing SDP pour extraire sampleRate, channels
                parseSdp(req.body);
                // Extraction du nom du client depuis l'User-Agent
                mClientName = req.headers.getOrDefault("User-Agent", "Appareil Apple");
                sendResponse(req, 200, "OK", null, null);
                break;

            case "SETUP":
                // Répond avec les ports RTP/RTCP côté serveur
                Map<String, String> setupHeaders = new HashMap<>();
                setupHeaders.put("Session",    mSessionId + ";timeout=60");
                setupHeaders.put("Transport",  "RTP/AVP/UDP;unicast;server_port=" + mRtpPort + ";" + (mRtpPort + 1));
                sendResponse(req, 200, "OK", setupHeaders, null);
                break;

            case "RECORD":
                // Début de la diffusion audio
                sendResponse(req, 200, "OK", Map.of("Audio-Latency", "2205"), null);
                break;

            case "SET_PARAMETER":
                // Contrôle du volume ou des métadonnées
                handleSetParameter(req);
                sendResponse(req, 200, "OK", null, null);
                break;

            case "GET_PARAMETER":
                sendResponse(req, 200, "OK", null, "volume: 0.000000\r\n");
                break;

            case "FLUSH":
                sendResponse(req, 200, "OK", null, null);
                break;

            case "TEARDOWN":
                sendResponse(req, 200, "OK", null, null);
                mActive = false;
                break;

            default:
                Log.w(TAG, "Méthode RTSP inconnue : " + req.method);
                sendResponse(req, 501, "Not Implemented", null, null);
        }
    }

    private void handleSetParameter(RtspRequest req) {
        if (req.body == null) return;
        for (String line : req.body.split("\r?\n")) {
            if (line.startsWith("volume:")) {
                try {
                    // Volume AirPlay : -144 (muet) à 0 (maximum)
                    float vol = Float.parseFloat(line.replace("volume:", "").trim());
                    Log.d(TAG, "Volume AirPlay : " + vol);
                    // Conversion vers AudioManager si nécessaire
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    /** Parse le SDP pour extraire les paramètres audio */
    private void parseSdp(String sdp) {
        if (sdp == null) return;
        for (String line : sdp.split("\r?\n")) {
            // a=rtpmap:96 AppleLossless
            if (line.startsWith("a=fmtp:")) {
                // Format : a=fmtp:96 <frameLen> 0 <bitDepth> 0 0 0 0 0 0 <sampleRate> <channels>
                String[] parts = line.substring(7).trim().split("\\s+");
                if (parts.length >= 12) {
                    try {
                        mSampleRate = Integer.parseInt(parts[11]);
                        mChannels   = Integer.parseInt(parts[12]);
                    } catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) {}
                }
            }
        }
        Log.d(TAG, "SDP : " + mSampleRate + "Hz, " + mChannels + " canaux");
    }

    // ─── Lecture / écriture RTSP ──────────────────────────────────────────────

    private RtspRequest readRequest() throws IOException {
        String requestLine = mReader.readLine();
        if (requestLine == null || requestLine.isEmpty()) return null;

        String[] parts = requestLine.split(" ", 3);
        if (parts.length < 2) return null;

        RtspRequest req = new RtspRequest();
        req.method  = parts[0];
        req.uri     = parts[1];
        req.headers = new HashMap<>();

        // Lire les en-têtes
        String line;
        int contentLength = 0;
        while ((line = mReader.readLine()) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                String key   = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                req.headers.put(key, value);
                if ("Content-Length".equalsIgnoreCase(key)) {
                    try { contentLength = Integer.parseInt(value); } catch (NumberFormatException ignored) {}
                }
            }
        }

        // Lire le corps si présent
        if (contentLength > 0) {
            char[] bodyChars = new char[contentLength];
            int read = mReader.read(bodyChars, 0, contentLength);
            if (read > 0) req.body = new String(bodyChars, 0, read);
        }

        return req;
    }

    private void sendResponse(RtspRequest req, int code, String message,
                              Map<String, String> extraHeaders, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append("RTSP/1.0 ").append(code).append(" ").append(message).append("\r\n");
        sb.append("CSeq: ").append(req.headers.getOrDefault("CSeq", "0")).append("\r\n");
        sb.append("Server: AirReceiverTV/1.0\r\n");
        sb.append("Date: ").append(new java.util.Date()).append("\r\n");

        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
        }

        if (body != null) {
            byte[] bodyBytes = body.getBytes();
            sb.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
            sb.append("\r\n");
            sb.append(body);
        } else {
            sb.append("Content-Length: 0\r\n\r\n");
        }

        mWriter.print(sb.toString());
        mWriter.flush();
    }

    // ─── Accesseurs ──────────────────────────────────────────────────────────

    public String getClientName() { return mClientName; }
    public int    getSampleRate()  { return mSampleRate; }
    public int    getChannels()    { return mChannels; }

    @Override
    public void close() {
        mActive = false;
        try { mSocket.close(); } catch (IOException ignored) {}
    }

    // ─── Classes internes ────────────────────────────────────────────────────

    private static class RtspRequest {
        String method;
        String uri;
        Map<String, String> headers;
        String body;
    }
}
