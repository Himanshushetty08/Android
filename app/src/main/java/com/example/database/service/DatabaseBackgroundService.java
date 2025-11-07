//package com.example.database.service;
//
//import android.app.Notification;
//import android.app.NotificationChannel;
//import android.app.NotificationManager;
//import android.app.Service;
//import android.content.ComponentName;
//import android.content.Intent;
//import android.content.ServiceConnection;
//import android.os.Build;
//import android.os.Handler;
//import android.os.HandlerThread;
//import android.os.IBinder;
//import android.os.Looper;
//import android.os.RemoteException;
//import android.util.Log;
//
//import androidx.annotation.Nullable;
//import androidx.core.app.NotificationCompat;
//
//import com.ultraviolette.fotaservice.IFotaS3Callback;
//import com.ultraviolette.fotaservice.IFotaS3Events;
//
//import java.util.ArrayList;
//import java.util.List;
//import java.util.concurrent.CopyOnWriteArrayList;
//
//import timber.log.Timber;
//
//public class DatabaseBackgroundService extends Service {
//
//    private static final String TAG = "DatabaseBackgroundService";
//    private Handler mainHandler;
//    private HandlerThread workerThread;
//    private Handler workerHandler;
//
//    private static final int INTERVAL_MINUTES = 15;
//    private static final long INTERVAL_MS = INTERVAL_MINUTES * 60 * 1000;
//
//    private static final int NOTIFICATION_ID = 1001;
//    private static final String CHANNEL_ID = "bike_data_service";
//    private static final String CHANNEL_NAME = "Bike Data Service";
//
//    private final List<IFotaS3Callback> callbacks = new ArrayList<>();
//    private NotificationManager notificationManager;
//
//    // ✅ FOTA Service binding
//    private int fotaBindAttempts = 0;
//    private static final int MAX_RETRY_ATTEMPTS = 5;
//    private static final long RETRY_INTERVAL_MS = 30_000; // retry every 30 seconds
//
//    // ✅ Maintain AIDL listener list safely
//
//    // ✅ Local AIDL binder for external apps to connect
//
//    @Override
//    public void onCreate() {
//        super.onCreate();
//        Timber.i("DatabaseBackgroundService created");
//
//        createNotificationChannel();
//        startForeground(NOTIFICATION_ID, createNotification("Service initializing..."));
//
//        mainHandler = new Handler(Looper.getMainLooper());
//        workerThread = new HandlerThread("DatabaseWorkerThread");
//        workerThread.start();
//        workerHandler = new Handler(workerThread.getLooper());
//
//        // Start FOTA binding
//        //bindToFotaService();
//
//        // Schedule periodic upload/download
//        scheduleRepeatingTask();
//    }
//
///*    private void bindToFotaService() {
//        Intent intent = new Intent();
//        intent.setComponent(new ComponentName(
//                "com.ultraviolette.fotaservice",
//                "com.ultraviolette.fotaservice.FotaService"
//        ));
//        try {
//            boolean bound = bindService(intent, fotaConnection, BIND_AUTO_CREATE);
//
//            if (bound) {
//                Log.i(TAG, "Successfully initiated binding to FotaService");
//                fotaBindAttempts = 0;
//            } else {
//                Log.e(TAG, "Failed to initiate binding to FotaService");
//                scheduleFotaServiceRetry();
//            }
//        } catch (SecurityException e) {
//            Log.e(TAG, "⚠ SecurityException binding to FotaService: " + e.getMessage());
//            scheduleFotaServiceRetry();
//        }
//    }*/
//
//    private final IFotaS3Events.Stub binder = new IFotaS3Events.Stub() {
//        @Override
//        public void fotaDownloadRequest(String s3Key) throws RemoteException {
//            Log.d(TAG, "Received FOTA download request for: " + s3Key);
//            notifyOtaAvailable(s3Key + "Return");
//        }
//
//        @Override
//        public void registerCallback(IFotaS3Callback callback) throws RemoteException {
//            synchronized (callbacks) {
//                if (!callbacks.contains(callback)) {
//                    callbacks.add(callback);
//                }
//            }
//            Log.d(TAG, "Callback registered");
//        }
//
//        @Override
//        public void unregisterCallback(IFotaS3Callback callback) throws RemoteException {
//            synchronized (callbacks) {
//                callbacks.remove(callback);
//            }
//            Log.d(TAG, "Callback unregistered");
//        }
//    };
//
//    private void notifyOtaAvailable(String payloadUrl) {
//        synchronized (callbacks) {
//            for (IFotaS3Callback cb : callbacks) {
//                try {
//                    cb.onOtaAvailable(payloadUrl);
//                } catch (RemoteException e) {
//                    Log.e(TAG, "Error notifying callback", e);
//                }
//            }
//        }
//    }
//
//    /*private final ServiceConnection fotaConnection = new ServiceConnection() {
//        @Override
//        public void onServiceConnected(ComponentName name, IBinder service) {
//            fotaService = IFotaCloudEvents.Stub.asInterface(service);
//            Log.i(TAG, " Bound to external FotaService");
//
//            if (fotaService != null) {
//                try {
//                    fotaService.sendOtaData("HIMU_v1.0.3", "https://update-server.com/firmware.bin");
//                    Log.i(TAG, "OTA data sent to FotaService");
//                } catch (RemoteException e) {
//                    Log.e(TAG, "RemoteException while sending OTA", e);
//                }
//            }
//        }
//
//        @Override
//        public void onServiceDisconnected(ComponentName name) {
//            fotaService = null;
//            Log.w(TAG, "⚠FotaService disconnected");
//            scheduleFotaServiceRetry();
//        }
//    };
//
//    private void scheduleFotaServiceRetry() {
//        if (fotaBindAttempts < MAX_RETRY_ATTEMPTS) {
//            fotaBindAttempts++;
//            mainHandler.postDelayed(this::bindToFotaService, RETRY_INTERVAL_MS);
//            Log.i(TAG, "Retrying FotaService bind (attempt " + fotaBindAttempts + ")");
//        } else {
//            Log.e(TAG, "Max retry attempts reached for FotaService binding");
//        }
//    }*/
//
//    private void scheduleRepeatingTask() {
//        workerHandler.post(new Runnable() {
//            @Override
//            public void run() {
//                performUploadDownload();
//                workerHandler.postDelayed(this, INTERVAL_MS);
//            }
//        });
//    }
//
//    private void performUploadDownload() {
//        try {
//            Timber.i(" Performing upload/download cycle...");
//            updateNotification("Uploading bike data...");
//            UploadManager.processFiles(getApplicationContext());
//
//            updateNotification(" Downloading files...");
//            CloudDownloadManager.processDownloads(getApplicationContext());
//
//            updateNotification("✅ Cycle complete - Next in 15 min");
//        } catch (Exception e) {
//            Timber.e(e, " Error in upload/download cycle");
//            updateNotification(" Error - retrying in 15 minutes");
//        }
//    }
//
//    private void processOtaData(String version, String payloadUrl) {
//        Timber.i(" Processing OTA v%s (%s)", version, payloadUrl);
//        updateNotification("Processing OTA v" + version + "...");
//        try {
//            // Example OTA handler
//            Timber.i(" Starting OTA download...");
//            // CloudDownloadManager.downloadOtaFile(getApplicationContext(), payloadUrl, version);
//            updateNotification(" OTA v" + version + " processed");
//        } catch (Exception e) {
//            Timber.e(e, "OTA processing failed");
//            updateNotification("OTA failed");
//        }
//    }
//
//    private void createNotificationChannel() {
//        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
//                notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
//            NotificationChannel channel = new NotificationChannel(
//                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
//            channel.setDescription("Bike data upload/download with OTA binding");
//            notificationManager.createNotificationChannel(channel);
//        }
//    }
//
//    private Notification createNotification(String message) {
//        String title = "Bike Data Service";
//        return new NotificationCompat.Builder(this, CHANNEL_ID)
//                .setContentTitle(title)
//                .setContentText(message)
//                .setSmallIcon(android.R.drawable.stat_sys_upload)
//                .setOngoing(true)
//                .build();
//    }
//
//    private void updateNotification(String message) {
//        if (notificationManager != null) {
//            notificationManager.notify(NOTIFICATION_ID, createNotification(message));
//        }
//    }
//
//    @Override
//    public int onStartCommand(Intent intent, int flags, int startId) {
//        Timber.i(" onStartCommand triggered");
//        updateNotification("Running periodic tasks...");
//        return START_STICKY;
//    }
//
//    @Override
//    public void onDestroy() {
//        Timber.w(" DatabaseBackgroundService destroyed");
//        if (workerHandler != null) workerHandler.removeCallbacksAndMessages(null);
//        if (workerThread != null) workerThread.quitSafely();
//        //unbindService(fotaConnection);
//        stopForeground(true);
//        super.onDestroy();
//    }
//
//    @Nullable
//    @Override
//    public IBinder onBind(Intent intent) {
//        Timber.i(" AIDL client binding to DatabaseBackgroundService");
//        return binder;
//    }
//}






package com.example.database.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.database.db.AppDatabase;
import com.example.database.db.FileDownloadDao;
import com.example.database.db.FileDownloadRecord;
import com.ultraviolette.fotaservice.IFotaS3Callback;
import com.ultraviolette.fotaservice.IFotaS3Events;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import timber.log.Timber;

public class DatabaseBackgroundService extends Service {

    private static final String TAG = "DatabaseBackgroundService";
    private Handler mainHandler;
    private HandlerThread workerThread;
    private Handler workerHandler;

    private static final int INTERVAL_MINUTES = 15;
    private static final long INTERVAL_MS = INTERVAL_MINUTES * 60 * 1000;

    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "bike_data_service";
    private static final String CHANNEL_NAME = "Bike Data Service";

    private final List<IFotaS3Callback> callbacks = new ArrayList<>();
    private NotificationManager notificationManager;

    // FOTA Service binding
    private int fotaBindAttempts = 0;
    private static final int MAX_RETRY_ATTEMPTS = 5;
    private static final long RETRY_INTERVAL_MS = 30_000; // retry every 30 seconds

    // Maintain AIDL listener list safely

    // Local AIDL binder for external apps to connect

    @Override
    public void onCreate() {
        super.onCreate();
        Timber.i("DatabaseBackgroundService created");

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification("Service initializing..."));

        mainHandler = new Handler(Looper.getMainLooper());
        workerThread = new HandlerThread("DatabaseWorkerThread");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());

        // Start FOTA binding
        //bindToFotaService();

        // Schedule periodic upload/download
        scheduleRepeatingTask();
    }

/*    private void bindToFotaService() {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(
                "com.ultraviolette.fotaservice",
                "com.ultraviolette.fotaservice.FotaService"
        ));
        try {
            boolean bound = bindService(intent, fotaConnection, BIND_AUTO_CREATE);

            if (bound) {
                Log.i(TAG, "Successfully initiated binding to FotaService");
                fotaBindAttempts = 0;
            } else {
                Log.e(TAG, "Failed to initiate binding to FotaService");
                scheduleFotaServiceRetry();
            }
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException binding to FotaService: " + e.getMessage());
            scheduleFotaServiceRetry();
        }
    }*/

    private final IFotaS3Events.Stub binder = new IFotaS3Events.Stub() {
        @Override
        public void fotaDownloadRequest(String s3Key) throws RemoteException {
            Log.d(TAG, "Received FOTA download request for: " + s3Key);
            Timber.i("FOTA REQUEST: s3Key = %s", s3Key);

            // EXTRACT FILENAME
            String fileName = "fota/fota.tar.xz";

            // TRIGGER DIRECT DOWNLOAD
            triggerOtaDownloadDirectly(fileName);

            // STILL NOTIFY CALLBACKS (as before)
            notifyOtaAvailable("SUCCESS");
        }

        @Override
        public void registerCallback(IFotaS3Callback callback) throws RemoteException {
            synchronized (callbacks) {
                if (!callbacks.contains(callback)) {
                    callbacks.add(callback);
                }
            }
            Log.d(TAG, "Callback registered");
        }

        @Override
        public void unregisterCallback(IFotaS3Callback callback) throws RemoteException {
            synchronized (callbacks) {
                callbacks.remove(callback);
            }
            Log.d(TAG, "Callback unregistered");
        }
    };

    private String extractFileName(String s3Key) {
        if (s3Key == null || s3Key.trim().isEmpty()) {
            Timber.w("s3Key is null or empty, using fallback");
            return "ota_fallback.bin";
        }
        int slash = s3Key.lastIndexOf('/');
        String name = slash >= 0 ? s3Key.substring(slash + 1) : s3Key;
        Timber.d("extractFileName: %s → %s", s3Key, name);
        return name;
    }

    // DIRECT DOWNLOAD — NO DATABASE DISCOVERY
    private void triggerOtaDownloadDirectly(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            Timber.e("triggerOtaDownloadDirectly: Invalid filename");
            return ;
        }

        Executors.newSingleThreadExecutor().execute(() -> {
            Timber.i("DIRECT OTA DOWNLOAD STARTED: %s", fileName);
            updateNotification("Downloading OTA: " + fileName);

            File downloadDir = new File("/data/local/tmp/cloud_download");
            try {
                if (!downloadDir.exists()) {
                    boolean created = downloadDir.mkdirs();
                    downloadDir.setReadable(true, false);
                    downloadDir.setWritable(true, false);
                    Timber.i("Download dir created: %s (created=%b)", downloadDir.getAbsolutePath(), created);
                }

                AppDatabase db = AppDatabase.getInstance(getApplicationContext());
                FileDownloadDao dao = db.fileDownloadDao();
                CloudDownloader downloader = new CloudDownloader(getApplicationContext(), dao);

                File targetFile = new File(downloadDir, fileName);
                FileDownloadRecord record = dao.getRecordByFileName(fileName);

                // ALREADY DOWNLOADED?
                if (record != null && "completed".equals(record.status) && targetFile.exists()) {
                    Timber.i("OTA ALREADY DOWNLOADED: %s (%d bytes)", fileName, targetFile.length());
                    updateNotification("OTA ready: " + fileName);
                    notifyOtaReady(fileName);
                    return;
                }

                // CREATE OR RESET RECORD
                if (record == null) {
                    record = new FileDownloadRecord(fileName, 0, "pending", null, System.currentTimeMillis(), 0);
                    long id = dao.insert(record);
                    Timber.d("New download record: id=%d, file=%s", id, fileName);
                } else {
                    record.status = "pending";
                    record.failureReason = null;
                    record.timestamp = System.currentTimeMillis();
                    dao.update(record);
                    Timber.d("Record reset to pending: %s", fileName);
                }

                // START DOWNLOAD
                Timber.i("DOWNLOADING OTA: %s → %s", fileName, targetFile.getAbsolutePath());
                downloader.downloadFile(record, targetFile);

                // SUCCESS?
                if ("completed".equals(record.status) && targetFile.exists()) {
                    Timber.i("OTA DOWNLOAD SUCCESS: %s (%d bytes)", fileName, targetFile.length());
                    updateNotification("OTA downloaded: " + fileName);
                    notifyOtaReady(fileName);
                } else {
                    String reason = record.failureReason != null ? record.failureReason : "Unknown";
                    Timber.w("OTA DOWNLOAD FAILED: %s | Reason: %s", fileName, reason);
                    updateNotification("OTA failed: " + fileName);
                }

            } catch (Exception e) {
                Timber.e(e, "FATAL: OTA download crashed for %s", fileName);
                updateNotification("OTA error");
            }
        });
    }

    private void notifyOtaReady(String fileName) {
        File file = new File("/data/local/tmp/cloud_download", fileName);
        if (file.exists()) {
            Timber.i("OTA FILE READY: %s (%d bytes)", file.getAbsolutePath(), file.length());

            // Optional: Send broadcast
            Intent intent = new Intent("com.ultraviolette.OTA_READY");
            intent.putExtra("file_path", file.getAbsolutePath());
            intent.putExtra("file_name", fileName);
            sendBroadcast(intent);
            Timber.d("Broadcast: com.ultraviolette.OTA_READY | %s", file.getAbsolutePath());
        }
    }

    private void notifyOtaAvailable(String payloadUrl) {
        synchronized (callbacks) {
            for (IFotaS3Callback cb : callbacks) {
                try {
                    cb.onOtaAvailable(payloadUrl);
                } catch (RemoteException e) {
                    Log.e(TAG, "Error notifying callback", e);
                }
            }
        }
    }

/*    private final ServiceConnection fotaConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            fotaService = IFotaCloudEvents.Stub.asInterface(service);
            Log.i(TAG, " Bound to external FotaService");

            if (fotaService != null) {
                try {
                    fotaService.sendOtaData("HIMU_v1.0.3", "https://update-server.com/firmware.bin");
                    Log.i(TAG, "OTA data sent to FotaService");
                } catch (RemoteException e) {
                    Log.e(TAG, "RemoteException while sending OTA", e);
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            fotaService = null;
            Log.w(TAG, "FotaService disconnected");
            scheduleFotaServiceRetry();
        }
    };

    private void scheduleFotaServiceRetry() {
        if (fotaBindAttempts < MAX_RETRY_ATTEMPTS) {
            fotaBindAttempts++;
            mainHandler.postDelayed(this::bindToFotaService, RETRY_INTERVAL_MS);
            Log.i(TAG, "Retrying FotaService bind (attempt " + fotaBindAttempts + ")");
        } else {
            Log.e(TAG, "Max retry attempts reached for FotaService binding");
        }
    }*/

    private void scheduleRepeatingTask() {
        workerHandler.post(new Runnable() {
            @Override
            public void run() {
                performUploadDownload();
                workerHandler.postDelayed(this, INTERVAL_MS);
            }
        });
    }

    private void performUploadDownload() {
        try {
            Timber.i(" Performing upload/download cycle...");
            updateNotification("Uploading bike data...");
            UploadManager.processFiles(getApplicationContext());

            updateNotification(" Downloading files...");
            CloudDownloadManager.processDownloads(getApplicationContext());

            updateNotification("Cycle complete - Next in 15 min");
        } catch (Exception e) {
            Timber.e(e, " Error in upload/download cycle");
            updateNotification(" Error - retrying in 15 minutes");
        }
    }

    private void processOtaData(String version, String payloadUrl) {
        Timber.i(" Processing OTA v%s (%s)", version, payloadUrl);
        updateNotification("Processing OTA v" + version + "...");
        try {
            // Example OTA handler
            Timber.i(" Starting OTA download...");
            // CloudDownloadManager.downloadOtaFile(getApplicationContext(), payloadUrl, version);
            updateNotification(" OTA v" + version + " processed");
        } catch (Exception e) {
            Timber.e(e, "OTA processing failed");
            updateNotification("OTA failed");
        }
    }

    private void createNotificationChannel() {
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Bike data upload/download with OTA binding");
            notificationManager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification(String message) {
        String title = "Bike Data Service";
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String message) {
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, createNotification(message));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Timber.i(" onStartCommand triggered");
        updateNotification("Running periodic tasks...");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Timber.w(" DatabaseBackgroundService destroyed");
        if (workerHandler != null) workerHandler.removeCallbacksAndMessages(null);
        if (workerThread != null) workerThread.quitSafely();
        //unbindService(fotaConnection);
        stopForeground(true);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Timber.i(" AIDL client binding to DatabaseBackgroundService");
        return binder;
    }
}