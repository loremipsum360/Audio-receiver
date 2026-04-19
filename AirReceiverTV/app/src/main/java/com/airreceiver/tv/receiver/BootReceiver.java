package com.airreceiver.tv.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.airreceiver.tv.service.AirPlayService;

/**
 * Démarre AirPlayService automatiquement au boot de la box Android TV.
 * Déclaré dans le Manifest avec BOOT_COMPLETED.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        Log.i(TAG, "Boot reçu : " + action);

        switch (action) {
            case Intent.ACTION_BOOT_COMPLETED:
            case "android.intent.action.QUICKBOOT_POWERON":
            case "com.htc.intent.action.QUICKBOOT_POWERON":
            case Intent.ACTION_MY_PACKAGE_REPLACED:
                startAirPlayService(context);
                break;
        }
    }

    private void startAirPlayService(Context context) {
        Intent serviceIntent = new Intent(context, AirPlayService.class);
        serviceIntent.setAction(AirPlayService.ACTION_START);

        // Android 8+ : démarrage foreground obligatoire pour les services en arrière-plan
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }

        Log.i(TAG, "AirPlayService démarré depuis le boot");
    }
}
