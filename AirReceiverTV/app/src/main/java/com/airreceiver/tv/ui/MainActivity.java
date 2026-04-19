package com.airreceiver.tv.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.View;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;

import com.airreceiver.tv.R;
import com.airreceiver.tv.service.AirPlayService;

/**
 * Activité principale — interface Android TV (Leanback).
 * Affiche l'état de la connexion AirPlay et l'IP de la box.
 * Le service AirPlay tourne indépendamment de cette activité.
 */
public class MainActivity extends FragmentActivity {

    private TextView mStatusText;
    private TextView mDeviceText;
    private TextView mIpText;
    private View     mIndicator;
    private View     mBarsContainer;

    private final BroadcastReceiver mStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean connected = intent.getBooleanExtra(AirPlayService.EXTRA_CONNECTED, false);
            String  device    = intent.getStringExtra(AirPlayService.EXTRA_DEVICE);
            updateUI(connected, device);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mStatusText    = findViewById(R.id.status_text);
        mDeviceText    = findViewById(R.id.device_text);
        mIpText        = findViewById(R.id.ip_text);
        mIndicator     = findViewById(R.id.status_indicator);
        mBarsContainer = findViewById(R.id.bars_container);

        // Affiche l'IP locale
        mIpText.setText(getLocalIpAddress());

        // Démarre le service AirPlay si pas encore actif
        startAirPlayService();

        // UI initiale : en attente
        updateUI(false, null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(AirPlayService.BROADCAST_STATE);
        registerReceiver(mStateReceiver, filter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mStateReceiver);
    }

    private void startAirPlayService() {
        Intent intent = new Intent(this, AirPlayService.class);
        intent.setAction(AirPlayService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void updateUI(boolean connected, String deviceName) {
        if (connected) {
            mStatusText.setText("Connecté");
            mDeviceText.setText(deviceName != null ? deviceName : "Appareil Apple");
            mIndicator.setBackgroundResource(R.drawable.indicator_green);
            mBarsContainer.setVisibility(View.VISIBLE);
        } else {
            mStatusText.setText("En attente…");
            mDeviceText.setText("Visible comme \"" + Build.MODEL + " AirPlay\"");
            mIndicator.setBackgroundResource(R.drawable.indicator_gray);
            mBarsContainer.setVisibility(View.INVISIBLE);
        }
    }

    private String getLocalIpAddress() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                int ip = wm.getConnectionInfo().getIpAddress();
                return Formatter.formatIpAddress(ip);
            }
        } catch (Exception ignored) {}
        return "Adresse inconnue";
    }
}
