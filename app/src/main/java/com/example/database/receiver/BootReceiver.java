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
import android.util.Log;


import com.example.database.service.DatabaseBackgroundService;




public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i(TAG, "📡 BootReceiver: Received action -> " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {

            Log.i(TAG, "⚙️ BootReceiver: Starting DatabaseBackgroundService...");

            Intent serviceIntent = new Intent(context, DatabaseBackgroundService.class);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                    Log.i(TAG, "✅ BootReceiver: Foreground service started successfully");
                } else {
                    context.startService(serviceIntent);
                    Log.i(TAG, "✅ BootReceiver: Background service started successfully");
                }
            } catch (Exception e) {
                Log.e(TAG, "❌ BootReceiver: Failed to start DatabaseBackgroundService", e);
            }
        }
    }
}