package com.airreceiver.tv.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.airreceiver.tv.R;
import com.airreceiver.tv.ui.MainActivity;
import com.airreceiver.tv.airplay.AirPlayServer;

/**
 * Service foreground qui héberge le serveur AirPlay.
 * Démarre automatiquement au boot via BootReceiver.
 * Reste actif même si l'UI est fermée.
 */
public class AirPlayService extends Service {

    private static final String TAG = "AirPlayService";
    public static final String CHANNEL_ID = "airplay_channel";
    public static final int NOTIFICATION_ID = 1001;

    // Actions Intent
    public static final String ACTION_START   = "com.airreceiver.tv.START";
    public static final String ACTION_STOP    = "com.airreceiver.tv.STOP";
    public static final String ACTION_STATUS  = "com.airreceiver.tv.STATUS";

    // Broadcast envoyé à l'UI pour mise à jour
    public static final String BROADCAST_STATE = "com.airreceiver.tv.STATE_CHANGED";
    public static final String EXTRA_CONNECTED  = "connected";
    public static final String EXTRA_DEVICE     = "device_name";
    public static final String EXTRA_IP         = "ip_address";

    private AirPlayServer mAirPlayServer;
    private WifiManager.MulticastLock mMulticastLock;
    private PowerManager.WakeLock mWakeLock;
    private boolean mRunning = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        acquireLocks();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!mRunning) {
            startForeground(NOTIFICATION_ID, buildNotification("En attente de connexion AirPlay…", false));
            startAirPlayServer();
            mRunning = true;
        }

        // START_STICKY : Android redémarre le service s'il est tué
        return START_STICKY;
    }

    private void startAirPlayServer() {
        String deviceName = getDeviceName();

        mAirPlayServer = new AirPlayServer(this, deviceName, new AirPlayServer.Listener() {

            @Override
            public void onClientConnected(String clientName) {
                Log.i(TAG, "Client connecté : " + clientName);
                updateNotification("Connecté à " + clientName, true);
                broadcastState(true, clientName);
            }

            @Override
            public void onClientDisconnected() {
                Log.i(TAG, "Client déconnecté");
                updateNotification("En attente de connexion AirPlay…", false);
                broadcastState(false, null);
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Erreur AirPlay : " + error);
                updateNotification("Erreur : " + error, false);
            }
        });

        mAirPlayServer.start();
        Log.i(TAG, "Serveur AirPlay démarré — nom : " + deviceName);
    }

    /** Renvoie un nom lisible pour la box (affiché dans la liste AirPlay Apple) */
    private String getDeviceName() {
        String name = Build.MODEL;
        if (name == null || name.isEmpty()) name = "Android TV";
        return name + " AirPlay";
    }

    // ─── Locks ───────────────────────────────────────────────────────────────

    private void acquireLocks() {
        // Multicast lock : nécessaire pour mDNS / Bonjour (découverte AirPlay)
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            mMulticastLock = wm.createMulticastLock("AirReceiverTV");
            mMulticastLock.setReferenceCounted(true);
            mMulticastLock.acquire();
        }

        // Wake lock partiel : empêche le CPU de dormir pendant la lecture
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AirReceiverTV::WakeLock");
            mWakeLock.acquire();
        }
    }

    private void releaseLocks() {
        if (mMulticastLock != null && mMulticastLock.isHeld()) mMulticastLock.release();
        if (mWakeLock != null && mWakeLock.isHeld()) mWakeLock.release();
    }

    // ─── Notifications ───────────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "AirPlay Receiver",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Réception audio AirPlay depuis iPhone / Mac");
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text, boolean connected) {
        Intent openUI = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openUI,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, AirPlayService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(connected ? "AirPlay — Connecté" : "AirPlay Receiver")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_airplay)
                .setContentIntent(pi)
                .addAction(R.drawable.ic_stop, "Arrêter", stopPi)
                .setOngoing(true)
                .setSilent(true)
                .build();
    }

    private void updateNotification(String text, boolean connected) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text, connected));
    }

    // ─── Broadcast vers l'UI ─────────────────────────────────────────────────

    private void broadcastState(boolean connected, String deviceName) {
        Intent intent = new Intent(BROADCAST_STATE);
        intent.putExtra(EXTRA_CONNECTED, connected);
        if (deviceName != null) intent.putExtra(EXTRA_DEVICE, deviceName);
        sendBroadcast(intent);
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mAirPlayServer != null) mAirPlayServer.stop();
        releaseLocks();
        mRunning = false;
        Log.i(TAG, "Service AirPlay arrêté");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // Service non lié
    }
}
