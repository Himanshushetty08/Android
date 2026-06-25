//    package com.example.database.receiver;
//
//    import static androidx.core.content.ContextCompat.getSystemService;
//
//    import android.content.BroadcastReceiver;
//    import android.content.Context;
//    import android.content.Intent;
//    import android.os.Build;
//    import android.util.Log;
//    import android.os.UserHandle;
//    import android.os.UserManager;
//
//    import com.example.database.service.DatabaseBackgroundService;
//
//    public class BootReceiver extends BroadcastReceiver {
//
//        private static final String TAG = "BootReceiver";
//
//        @Override
//        public void onReceive(Context context, Intent intent) {
//
//            ///
//
//            UserManager um = (UserManager) getSystemService(Context.USER_SERVICE);
//            if (!um.isSystemUser()) return;
//
//            ///
//            String action = intent.getAction();
//            Log.i(TAG, " BootReceiver: Received action -> " + action);
//
//            if (Intent.ACTION_BOOT_COMPLETED.equals(action)
//                    || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
//                    || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
//
//                Log.i(TAG, "⚙ BootReceiver: Starting DatabaseBackgroundService...");
//
//                Intent serviceIntent = new Intent(context, DatabaseBackgroundService.class);
//                try {
//                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//                        context.startForegroundService(serviceIntent);
//                        Log.i(TAG, " BootReceiver: Foreground service started successfullyy");
//                    } else {
//                        context.startService(serviceIntent);
//                        Log.i(TAG, " BootReceiver: Background service started successfullyy");
//                    }
//                } catch (Exception e) {
//                    Log.e(TAG, "BootReceiver: Failed to start DatabaseBackgroundService", e);
//                }
//
//
//            }
//        }
//
//    }
//
//
//




package com.example.database.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.UserManager;
import android.util.Log;

import android.content.pm.PackageManager;
import com.example.database.service.DatabaseBackgroundService;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {

        UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
        if (um == null || !um.isSystemUser()) return;

        String action = intent.getAction();
        Log.i(TAG, " BootReceiver: Received action -> " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {

            Log.i(TAG, "hellooopppp youuuu oooBootReceiver: Starting DatabaseBackgroundServiceuuuuuuu00yuyuuyuu00...");

            Intent serviceIntent = new Intent(context, DatabaseBackgroundService.class);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                    Log.i(TAG, " BootReceiver: Foreground service started successfullyy");
                } else {
                    context.startService(serviceIntent);
                    Log.i(TAG, " BootReceiver: Background service started suyyccessfullyy");
                }
            } catch (Exception e) {
                Log.e(TAG, "BootReceiver: Failed to start DatabaseBackgroundService", e);
            }
        }


//        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
//                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
//                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
//
//            Log.i(TAG, "⚙ BootReceiver: Starting DatabaseBackgroundService...");
//
//            // Auto install in User 10 so broadcast forwarding works
//            // Auto install in User 10 so broadcast forwarding works
//            try {
//                PackageManager pm = context.getPackageManager();
//                java.lang.reflect.Method method = pm.getClass().getMethod(
//                        "installExistingPackageAsUser", String.class, int.class);
//                method.invoke(pm, "com.example.database", 10);
//                Log.i(TAG, "Auto installed com.example.database for User 10");
//            } catch (Exception e) {
//                Log.e(TAG, "Failed to install for User 10", e);
//            }
//            Intent serviceIntent = new Intent(context, DatabaseBackgroundService.class);
//            try {
//                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//                    context.startForegroundService(serviceIntent);
//                } else {
//                    context.startService(serviceIntent);
//                }
//            } catch (Exception e) {
//                Log.e(TAG, "BootReceiver: Failed to start DatabaseBackgroundService", e);
//            }
//        }
    }
}