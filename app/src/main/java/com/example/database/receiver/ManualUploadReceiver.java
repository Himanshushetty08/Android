

package com.example.database.receiver;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.UserManager;
import android.util.Log;

import com.example.database.service.UploadManager;
import com.example.database.utils.DeviceSession;
import com.example.database.utils.PersistentImeiStore;

public class ManualUploadReceiver extends BroadcastReceiver {

    private static final String TAG = "ManualUploadReceiver";
    private static final String ACTION =
            "com.example.database.ACTION_MANUAL_UPLOAD";



    @SuppressLint("SuspiciousIndentation")
    @Override
    public void onReceive(Context context, Intent intent) {
        UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);

        if (um == null) return;


        // existing code below — only runs in User 0
        if (intent == null || !ACTION.equals(intent.getAction())) {
            Log.w(TAG, "Ignoring unknown action");
            return;
        }

            // existing code below

//        PersistentImeiStore.debugDump(context);

        if (intent == null || !ACTION.equals(intent.getAction())) {
            Log.w(TAG, "Ignoring unknown action");
            return;
        }

        Log.w(TAG, "========================================");
        Log.w(TAG, "MANUAL UPLOAD TRIGGER SUCKIINGGGGGiiiiooi RECEIVEDgggssttttioioiouufufufufufufufufus");
        Log.w(TAG, "========================================");

        // Step 1: Try runtime IMEI first
        String imei ;

        // Step 2: Fallback to persistent IMEI if runtime missing

         imei = PersistentImeiStore.load();
            Log.i(TAG, "Fallback IMEI from persistent storage = " + imei);

        // Step 3: Still missing → skip upload safely
        if (imei == null || imei.isEmpty()) {

            Log.e(TAG,
                    "IMEI unavailable (runtime + persistent) — skipping manual upload");

            return;
        }

        DeviceSession.setImei(imei);

        Log.i(TAG, "Manual upload using IMEI = " + imei);

        // Step 4: Trigger full upload pipeline (same as scheduler/WiFi trigger)
        UploadManager.processFiles(context.getApplicationContext());

        Log.i(TAG, "Manual upload pipeline started successfully");
    }
}












