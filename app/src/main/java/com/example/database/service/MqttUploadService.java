// File: MqttUploadService.java
package com.example.database.service;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import androidx.annotation.Nullable;

import com.example.database.utils.DeviceSession;


import com.example.database.utils.PersistentImeiStore;
import com.ultraviolette.uvmqtt.IMqttFileHandler;

public class MqttUploadService extends Service {

    private static final String TAG = "MqttUploadService";

    private final IMqttFileHandler.Stub binder = new IMqttFileHandler.Stub() {
        @Override
        public void uploadFile(String file_name) throws RemoteException {
            Log.w(TAG, "MQTT COMMAND RECEIVED → uploadFile(\"" + file_name + "\")");

//            if ("upload_now".equalsIgnoreCase(file_name) ||
//                    file_name == null || file_name.isEmpty()) {
//
//                UploadManager.processFilesForcedEmergency(MqttUploadService.this);
//
//            } else {
//                Log.w(TAG, "Unknown file command: " + file_name + " — ignoring");
//            }
        }



        @Override
        public void imei(String imei) throws RemoteException {
            Log.w(TAG, "MQTT COMMAND RECEIVED → imei(\"" + imei + "\")");

            DeviceSession.setImei(imei);
            PersistentImeiStore.save(imei);


    }


    @Override
        public void uploadCommand(String command) throws RemoteException {
            Log.w(TAG, "MQTT COMMAND RECEIVED → uploadCommand(\"" + command + "\")");

//            if ("upload_now".equalsIgnoreCase(command) ||
//                    command == null || command.isEmpty()) {
//
//               UploadManager.processFilesForcedEmergency(MqttUploadService.this);
//
//            } else {
//                Log.w(TAG, "Unknown command: " + command + " — ignoring");
//            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "MqttUploadService created — ready for MQTT commands");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "Client bound to MqttUploadService");
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i(TAG, "Client unbound from MqttUploadService");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "MqttUploadService destroyed");
        super.onDestroy();
    }
}
