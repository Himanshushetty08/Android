
package com.example.database.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;


import com.example.database.service.DatabaseBackgroundService;




public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i(TAG, " BootReceiver: Received action -> " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {

            Log.i(TAG, "⚙ BootReceiver: Starting DatabaseBackgroundService...");

            Intent serviceIntent = new Intent(context, DatabaseBackgroundService.class);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                    Log.i(TAG, " BootReceiver: Foreground service started successfully");
                } else {
                    context.startService(serviceIntent);
                    Log.i(TAG, " BootReceiver: Background service started successfully");
                }
            } catch (Exception e) {
                Log.e(TAG, "BootReceiver: Failed to start DatabaseBackgroundService", e);
            }
        }
    }
}