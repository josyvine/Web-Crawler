package org.chromium.chrome.browser.monitoring;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "KiwiBootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }

        String action = intent.getAction();
        Log.i(TAG, "Boot intent captured: " + action);

        // Capture standard system boot complete actions, including HTC/Qualcomm quickboot triggers
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) 
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {

            Log.i(TAG, "Re-enabling active crawler timelines following device reboot...");

            try {
                Intent serviceIntent = new Intent(context, MonitoringService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
                Log.i(TAG, "MonitoringService background bootloader process successfully initialized.");
            } catch (Exception e) {
                Log.e(TAG, "Failed to restore background services on device startup: ", e);
            }
        }
    }
}