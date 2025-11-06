//package com.example.database.receiver;
//
//import android.content.BroadcastReceiver;
//import android.content.Context;
//import android.content.Intent;
//import com.example.database.service.DatabaseBackgroundService;
//import timber.log.Timber;
//
//public class BootReceiver extends BroadcastReceiver {
//
//    @Override
//    public void onReceive(Context context, Intent intent) {
//        String action = intent.getAction();
//
//        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
//                Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) ||
//                Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
//
//            Timber.i("🚀 BOOT: Starting database background service");
//
//            // Start the background service
//            Intent serviceIntent = new Intent(context, DatabaseBackgroundService.class);
//            context.startService(serviceIntent);
//
//            Timber.i("✅ Database service started automatically");
//        }
//    }
//}



//package com.example.database.receiver;
//
//import android.content.BroadcastReceiver;
//import android.content.Context;
//import android.content.Intent;
//import android.os.Build;
//import com.example.database.service.DatabaseBackgroundService;
//import timber.log.Timber;
//
//public class BootReceiver extends BroadcastReceiver {
//
//    @Override
//    public void onReceive(Context context, Intent intent) {
//        String action = intent.getAction();
//
//        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
//                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
//                || Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
//
//            Timber.i("🚀 BOOT: Starting DatabaseBackgroundService");
//
//            Intent serviceIntent = new Intent(context, DatabaseBackgroundService.class);
//
//            try {
//                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//                    context.startForegroundService(serviceIntent);
//                } else {
//                    context.startService(serviceIntent);
//                }
//                Timber.i("✅ DatabaseBackgroundService started successfully");
//            } catch (Exception e) {
//                Timber.e(e, "❌ Failed to start DatabaseBackgroundService");
//            }
//        }
//    }
//}




























//package com.example.database.receiver;
//
//import android.content.BroadcastReceiver;
//import android.content.Context;
//import android.content.Intent;
//import android.os.Build;
//
//import com.example.database.service.DatabaseBackgroundService;
//
//import timber.log.Timber;
//
//public class BootReceiver extends BroadcastReceiver {
//
//    @Override
//    public void onReceive(Context context, Intent intent) {
//        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) ||
//                Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
//
//            Timber.i("📡 BootReceiver: Device boot completed, starting service...");
//
//            Intent serviceIntent = new Intent(context, DatabaseBackgroundService.class);
//            try {
//                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//                    context.startForegroundService(serviceIntent);
//                } else {
//                    context.startService(serviceIntent);
//                }
//                Timber.i("✅ BootReceiver: DatabaseBackgroundService started successfully");
//            } catch (Exception e) {
//                Timber.e(e, "❌ BootReceiver: Failed to start service");
//            }
//        }
//    }
//}






















package com.example.database.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import com.example.database.service.DatabaseBackgroundService;

import timber.log.Timber;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Timber.i("📡 BootReceiver: Received action -> %s", action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_PACKAGE_REPLACED.equals(action)) {

            Toast.makeText(context, "BootReceiver triggered", Toast.LENGTH_SHORT).show();
            Timber.i("⚙️ BootReceiver: Preparing to start DatabaseBackgroundService...");

            // ✅ Check if battery optimizations are blocking the app
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    String packageName = context.getPackageName();
                    android.os.PowerManager pm =
                            (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
                    if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                        Timber.w("⚠️ BootReceiver: Battery optimization is ON for this app");
                        Intent settingsIntent =
                                new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                        settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(settingsIntent);
                    }
                }
            } catch (Exception e) {
                Timber.e(e, "❌ BootReceiver: Failed to check battery optimization status");
            }

            // ✅ Delay actual start slightly to ensure boot processes finish
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                Intent serviceIntent = new Intent(context, DatabaseBackgroundService.class);
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent);
                        Timber.i("✅ BootReceiver: Foreground service started successfully");
                    } else {
                        context.startService(serviceIntent);
                        Timber.i("✅ BootReceiver: Background service started successfully");
                    }
                } catch (Exception e) {
                    Timber.e(e, "❌ BootReceiver: Failed to start DatabaseBackgroundService");
                }
            }, 6000); // ⏱️ 6 seconds delay after boot
        }
    }
}
