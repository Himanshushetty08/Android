package com.ultraviolette.s3service.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class EmergencyUploadReceiver extends BroadcastReceiver {

    private static final String TAG = "EmergencyUploadReceiver";
    public static final String ACTION_EMERGENCY_UPLOAD = "com.ultraviolette.ACTION_EMERGENCY_UPLOAD";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        String action = intent.getAction();
        String cmd = intent.getStringExtra("action");

        if (!ACTION_EMERGENCY_UPLOAD.equals(action) && !"upload_now".equals(cmd)) {
            return;
        }

        Log.w(TAG, "EMERGENCY UPLOAD COMMAND RECEIVED!");



        if (cmd != null) {
            Log.w(TAG, "Payload received → action: \"" + cmd + "\"");
        }

        // THIS IS THE ONLY LINE THAT MATTERS
        UploadManager.processFilesForcedEmergency(context.getApplicationContext());
    }
}