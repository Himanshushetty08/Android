////////package com.example.database.service;
////////
////////import android.app.Notification;
////////import android.app.NotificationChannel;
////////import android.app.NotificationManager;
////////import android.app.Service;
////////import android.content.ComponentName;
////////import android.content.Intent;
////////import android.content.ServiceConnection;
////////import android.os.Build;
////////import android.os.Handler;
////////import android.os.HandlerThread;
////////import android.os.IBinder;
////////import android.os.Looper;
////////import android.os.RemoteException;
////////import android.util.Log;
////////
////////import androidx.annotation.Nullable;
////////import androidx.core.app.NotificationCompat;
////////
////////import com.ultraviolette.fotaservice.IFotaS3Callback;
////////import com.ultraviolette.fotaservice.IFotaS3Events;
////////
////////import java.util.ArrayList;
////////import java.util.List;
////////import java.util.concurrent.CopyOnWriteArrayList;
////////
////////import timber.log.Timber;
////////
////////public class DatabaseBackgroundService extends Service {
////////
////////    private static final String TAG = "DatabaseBackgroundService";
////////    private Handler mainHandler;
////////    private HandlerThread workerThread;
////////    private Handler workerHandler;
////////
////////    private static final int INTERVAL_MINUTES = 15;
////////    private static final long INTERVAL_MS = INTERVAL_MINUTES * 60 * 1000;
////////
////////    private static final int NOTIFICATION_ID = 1001;
////////    private static final String CHANNEL_ID = "bike_data_service";
////////    private static final String CHANNEL_NAME = "Bike Data Service";
////////
////////    private final List<IFotaS3Callback> callbacks = new ArrayList<>();
////////    private NotificationManager notificationManager;
////////
////////    // ✅ FOTA Service binding
////////    private int fotaBindAttempts = 0;
////////    private static final int MAX_RETRY_ATTEMPTS = 5;
////////    private static final long RETRY_INTERVAL_MS = 30_000; // retry every 30 seconds
////////
////////    // ✅ Maintain AIDL listener list safely
////////
////////    // ✅ Local AIDL binder for external apps to connect
////////
////////    @Override
////////    public void onCreate() {
////////        super.onCreate();
////////        Timber.i("DatabaseBackgroundService created");
////////
////////        createNotificationChannel();
////////        startForeground(NOTIFICATION_ID, createNotification("Service initializing..."));
////////
////////        mainHandler = new Handler(Looper.getMainLooper());
////////        workerThread = new HandlerThread("DatabaseWorkerThread");
////////        workerThread.start();
////////        workerHandler = new Handler(workerThread.getLooper());
////////
////////        // Start FOTA binding
////////        //bindToFotaService();
////////
////////        // Schedule periodic upload/download
////////        scheduleRepeatingTask();
////////    }
////////
/////////*    private void bindToFotaService() {
////////        Intent intent = new Intent();
////////        intent.setComponent(new ComponentName(
////////                "com.ultraviolette.fotaservice",
////////                "com.ultraviolette.fotaservice.FotaService"
////////        ));
////////        try {
////////            boolean bound = bindService(intent, fotaConnection, BIND_AUTO_CREATE);
////////
////////            if (bound) {
////////                Log.i(TAG, "Successfully initiated binding to FotaService");
////////                fotaBindAttempts = 0;
////////            } else {
////////                Log.e(TAG, "Failed to initiate binding to FotaService");
////////                scheduleFotaServiceRetry();
////////            }
////////        } catch (SecurityException e) {
////////            Log.e(TAG, "⚠ SecurityException binding to FotaService: " + e.getMessage());
////////            scheduleFotaServiceRetry();
////////        }
////////    }*/
////////
////////    private final IFotaS3Events.Stub binder = new IFotaS3Events.Stub() {
////////        @Override
////////        public void fotaDownloadRequest(String s3Key) throws RemoteException {
////////            Log.d(TAG, "Received FOTA download request for: " + s3Key);
////////            notifyOtaAvailable(s3Key + "Return");
////////        }
////////
////////        @Override
////////        public void registerCallback(IFotaS3Callback callback) throws RemoteException {
////////            synchronized (callbacks) {
////////                if (!callbacks.contains(callback)) {
////////                    callbacks.add(callback);
////////                }
////////            }
////////            Log.d(TAG, "Callback registered");
////////        }
////////
////////        @Override
////////        public void unregisterCallback(IFotaS3Callback callback) throws RemoteException {
////////            synchronized (callbacks) {
////////                callbacks.remove(callback);
////////            }
////////            Log.d(TAG, "Callback unregistered");
////////        }
////////    };
////////
////////    private void notifyOtaAvailable(String payloadUrl) {
////////        synchronized (callbacks) {
////////            for (IFotaS3Callback cb : callbacks) {
////////                try {
////////                    cb.onOtaAvailable(payloadUrl);
////////                } catch (RemoteException e) {
////////                    Log.e(TAG, "Error notifying callback", e);
////////                }
////////            }
////////        }
////////    }
////////
////////    /*private final ServiceConnection fotaConnection = new ServiceConnection() {
////////        @Override
////////        public void onServiceConnected(ComponentName name, IBinder service) {
////////            fotaService = IFotaCloudEvents.Stub.asInterface(service);
////////            Log.i(TAG, " Bound to external FotaService");
////////
////////            if (fotaService != null) {
////////                try {
////////                    fotaService.sendOtaData("HIMU_v1.0.3", "https://update-server.com/firmware.bin");
////////                    Log.i(TAG, "OTA data sent to FotaService");
////////                } catch (RemoteException e) {
////////                    Log.e(TAG, "RemoteException while sending OTA", e);
////////                }
////////            }
////////        }
////////
////////        @Override
////////        public void onServiceDisconnected(ComponentName name) {
////////            fotaService = null;
////////            Log.w(TAG, "⚠FotaService disconnected");
////////            scheduleFotaServiceRetry();
////////        }
////////    };
////////
////////    private void scheduleFotaServiceRetry() {
////////        if (fotaBindAttempts < MAX_RETRY_ATTEMPTS) {
////////            fotaBindAttempts++;
////////            mainHandler.postDelayed(this::bindToFotaService, RETRY_INTERVAL_MS);
////////            Log.i(TAG, "Retrying FotaService bind (attempt " + fotaBindAttempts + ")");
////////        } else {
////////            Log.e(TAG, "Max retry attempts reached for FotaService binding");
////////        }
////////    }*/
////////
////////    private void scheduleRepeatingTask() {
////////        workerHandler.post(new Runnable() {
////////            @Override
////////            public void run() {
////////                performUploadDownload();
////////                workerHandler.postDelayed(this, INTERVAL_MS);
////////            }
////////        });
////////    }
////////
////////    private void performUploadDownload() {
////////        try {
////////            Timber.i(" Performing upload/download cycle...");
////////            updateNotification("Uploading bike data...");
////////            UploadManager.processFiles(getApplicationContext());
////////
////////            updateNotification(" Downloading files...");
////////            CloudDownloadManager.processDownloads(getApplicationContext());
////////
////////            updateNotification("✅ Cycle complete - Next in 15 min");
////////        } catch (Exception e) {
////////            Timber.e(e, " Error in upload/download cycle");
////////            updateNotification(" Error - retrying in 15 minutes");
////////        }
////////    }
////////
////////    private void processOtaData(String version, String payloadUrl) {
////////        Timber.i(" Processing OTA v%s (%s)", version, payloadUrl);
////////        updateNotification("Processing OTA v" + version + "...");
////////        try {
////////            // Example OTA handler
////////            Timber.i(" Starting OTA download...");
////////            // CloudDownloadManager.downloadOtaFile(getApplicationContext(), payloadUrl, version);
////////            updateNotification(" OTA v" + version + " processed");
////////        } catch (Exception e) {
////////            Timber.e(e, "OTA processing failed");
////////            updateNotification("OTA failed");
////////        }
////////    }
////////
////////    private void createNotificationChannel() {
////////        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
////////        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
////////                notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
////////            NotificationChannel channel = new NotificationChannel(
////////                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
////////            channel.setDescription("Bike data upload/download with OTA binding");
////////            notificationManager.createNotificationChannel(channel);
////////        }
////////    }
////////
////////    private Notification createNotification(String message) {
////////        String title = "Bike Data Service";
////////        return new NotificationCompat.Builder(this, CHANNEL_ID)
////////                .setContentTitle(title)
////////                .setContentText(message)
////////                .setSmallIcon(android.R.drawable.stat_sys_upload)
////////                .setOngoing(true)
////////                .build();
////////    }
////////
////////    private void updateNotification(String message) {
////////        if (notificationManager != null) {
////////            notificationManager.notify(NOTIFICATION_ID, createNotification(message));
////////        }
////////    }
////////
////////    @Override
////////    public int onStartCommand(Intent intent, int flags, int startId) {
////////        Timber.i(" onStartCommand triggered");
////////        updateNotification("Running periodic tasks...");
////////        return START_STICKY;
////////    }
////////
////////    @Override
////////    public void onDestroy() {
////////        Timber.w(" DatabaseBackgroundService destroyed");
////////        if (workerHandler != null) workerHandler.removeCallbacksAndMessages(null);
////////        if (workerThread != null) workerThread.quitSafely();
////////        //unbindService(fotaConnection);
////////        stopForeground(true);
////////        super.onDestroy();
////////    }
////////
////////    @Nullable
////////    @Override
////////    public IBinder onBind(Intent intent) {
////////        Timber.i(" AIDL client binding to DatabaseBackgroundService");
////////        return binder;
////////    }
////////}
//////
//////
//////
//////
//////
//////
//////
////////package com.example.database.service;
////////
////////import android.app.Notification;
////////import android.app.NotificationChannel;
////////import android.app.NotificationManager;
////////import android.app.Service;
////////import android.content.ComponentName;
////////import android.content.Intent;
////////import android.content.ServiceConnection;
////////import android.os.Build;
////////import android.os.Handler;
////////import android.os.HandlerThread;
////////import android.os.IBinder;
////////import android.os.Looper;
////////import android.os.RemoteException;
////////import android.util.Log;
////////
////////import androidx.annotation.Nullable;
////////import androidx.core.app.NotificationCompat;
////////
////////import com.example.database.db.AppDatabase;
////////import com.example.database.db.FileDownloadDao;
////////import com.example.database.db.FileDownloadRecord;
////////import com.ultraviolette.fotaservice.IFotaS3Callback;
////////import com.ultraviolette.fotaservice.IFotaS3Events;
////////
////////import java.io.File;
////////import java.util.ArrayList;
////////import java.util.List;
////////import java.util.concurrent.Executors;
////////
////////public class DatabaseBackgroundService extends Service {
////////
////////    private static final String TAG = "DatabaseBackgroundService";
////////    private Handler mainHandler;
////////    private HandlerThread workerThread;
////////    private Handler workerHandler;
////////
////////    private static final int INTERVAL_MINUTES = 15;
////////    private static final long INTERVAL_MS = INTERVAL_MINUTES * 60 * 1000;
////////
////////    private static final int NOTIFICATION_ID = 1001;
////////    private static final String CHANNEL_ID = "bike_data_service";
////////    private static final String CHANNEL_NAME = "Bike Data Service";
////////
////////    private final List<IFotaS3Callback> callbacks = new ArrayList<>();
////////    private NotificationManager notificationManager;
////////
////////    // FOTA Service binding
////////    private int fotaBindAttempts = 0;
////////    private static final int MAX_RETRY_ATTEMPTS = 5;
////////    private static final long RETRY_INTERVAL_MS = 30_000; // retry every 30 seconds
////////
////////    @Override
////////    public void onCreate() {
////////        super.onCreate();
////////        Log.i(TAG, "DatabaseBackgroundService created");
////////
////////        createNotificationChannel();
////////        startForeground(NOTIFICATION_ID, createNotification("Service initializing..."));
////////
////////        mainHandler = new Handler(Looper.getMainLooper());
////////        workerThread = new HandlerThread("DatabaseWorkerThread");
////////        workerThread.start();
////////        workerHandler = new Handler(workerThread.getLooper());
////////
////////        scheduleRepeatingTask();
////////    }
////////
////////    private final IFotaS3Events.Stub binder = new IFotaS3Events.Stub() {
////////        @Override
////////        public void fotaDownloadRequest(String s3Key) throws RemoteException {
////////            Log.d(TAG, "Received FOTA download request for: " + s3Key);
////////            Log.i(TAG, "FOTA REQUEST: s3Key = " + s3Key);
////////
////////            String fileName = "fota/fota.tar";
////////            triggerOtaDownloadDirectly(fileName);
////////            // REMOVED: notifyOtaAvailable("SUCCESS") — now called only after success
////////        }
////////
////////        @Override
////////        public void registerCallback(IFotaS3Callback callback) throws RemoteException {
////////            synchronized (callbacks) {
////////                if (!callbacks.contains(callback)) {
////////                    callbacks.add(callback);
////////                }
////////            }
////////            Log.d(TAG, "Callback registered");
////////        }
////////
////////        @Override
////////        public void unregisterCallback(IFotaS3Callback callback) throws RemoteException {
////////            synchronized (callbacks) {
////////                callbacks.remove(callback);
////////            }
////////            Log.d(TAG, "Callback unregistered");
////////        }
////////    };
////////
////////    private String extractFileName(String s3Key) {
////////        if (s3Key == null || s3Key.trim().isEmpty()) {
////////            Log.w(TAG, "s3Key is null or empty, using fallback");
////////            return "ota_fallback.bin";
////////        }
////////        int slash = s3Key.lastIndexOf('/');
////////        String name = slash >= 0 ? s3Key.substring(slash + 1) : s3Key;
////////        Log.d(TAG, "extractFileName: " + s3Key + " to " + name);
////////        return name;
////////    }
////////
////////    private void triggerOtaDownloadDirectly(String fileName) {
////////        if (fileName == null || fileName.trim().isEmpty()) {
////////            Log.e(TAG, "triggerOtaDownloadDirectly: Invalid filename");
////////            notifyOtaAvailable("FAILED");
////////            return;
////////        }
////////
////////        Executors.newSingleThreadExecutor().execute(() -> {
////////            Log.i(TAG, "DIRECT OTA DOWNLOAD STARTED: " + fileName);
////////            updateNotification("Downloading OTA: " + fileName);
////////
////////            File downloadDir = new File("/data/vendor/uv_fota/fota");
////////            try {
////////                if (!downloadDir.exists()) {
////////                    boolean created = downloadDir.mkdirs();
////////                    downloadDir.setReadable(true, false);
////////                    downloadDir.setWritable(true, false);
////////                    Log.i(TAG, "Download dir created: " + downloadDir.getAbsolutePath() + " (created=" + created + ")");
////////                }
////////
////////                AppDatabase db = AppDatabase.getInstance(getApplicationContext());
////////                FileDownloadDao dao = db.fileDownloadDao();
////////                CloudDownloader downloader = new CloudDownloader(getApplicationContext(), dao);
////////
////////                File targetFile = new File(downloadDir, fileName);
////////                FileDownloadRecord record = dao.getRecordByFileName(fileName);
////////
////////                if (record != null && "completed".equals(record.status) && targetFile.exists()) {
////////                    Log.i(TAG, "OTA ALREADY DOWNLOADED: " + fileName + " (" + targetFile.length() + " bytes)");
////////                    updateNotification("OTA ready: " + fileName);
////////                    notifyOtaReady(fileName);
////////                    notifyOtaAvailable("SUCCESS");  // Already exists → SUCCESS
////////                    return;
////////                }
////////
////////                if (record == null) {
////////                    record = new FileDownloadRecord(fileName, 0, "pending", null, System.currentTimeMillis(), 0);
////////                    long id = dao.insert(record);
////////                    Log.d(TAG, "New download record: id=" + id + ", file=" + fileName);
////////                } else {
////////                    record.status = "pending";
////////                    record.failureReason = null;
////////                    record.timestamp = System.currentTimeMillis();
////////                    dao.update(record);
////////                    Log.d(TAG, "Record reset to pending: " + fileName);
////////                }
////////
////////                Log.i(TAG, "DOWNLOADING OTA: " + fileName + " to " + targetFile.getAbsolutePath());
////////                String failureReason = downloader.downloadFile(record, targetFile);
////////
////////                if ("completed".equals(record.status) && targetFile.exists()) {
////////                    Log.i(TAG, "OTA DOWNLOAD SUCCESS: " + fileName + " (" + targetFile.length() + " bytes)");
////////                    updateNotification("OTA downloaded: " + fileName);
////////                    notifyOtaReady(fileName);
////////                    notifyOtaAvailable("SUCCESS");  // ONLY ON SUCCESS
////////                } else {
////////                    String reason = failureReason != null ? failureReason : "Unknown";
////////                    Log.w(TAG, "OTA DOWNLOAD FAILED: " + fileName + " | Reason: " + reason);
////////                    updateNotification("OTA failed: " + fileName + " (" + reason + ")");
////////
////////                    if ("FILE_NOT_FOUND".equals(reason)) {
////////                        Log.e(TAG, "FILE NOT FOUND ON S3 — Check Lambda key: " + fileName);
////////                    } else if ("NETWORK_ERROR".equals(reason)) {
////////                        Log.e(TAG, "NETWORK ISSUE — Retry in 30 min");
////////                    }
////////                    notifyOtaAvailable("FAILED");  // ON ANY FAILURE
////////                }
////////
////////            } catch (Exception e) {
////////                Log.e(TAG, "FATAL: OTA download crashed for " + fileName, e);
////////                updateNotification("OTA error: " + e.getMessage());
////////                notifyOtaAvailable("FAILED");
////////            }
////////        });
////////    }
////////
////////    private void notifyOtaReady(String fileName) {
////////        File file = new File("/data/vendor/uv_fota/fota", fileName);
////////        if (file.exists()) {
////////            Log.i(TAG, "OTA FILE READY: " + file.getAbsolutePath() + " (" + file.length() + " bytes)");
////////
////////            Intent intent = new Intent("com.ultraviolette.OTA_READY");
////////            intent.putExtra("file_path", file.getAbsolutePath());
////////            intent.putExtra("file_name", fileName);
////////            sendBroadcast(intent);
////////            Log.d(TAG, "Broadcast: com.ultraviolette.OTA_READY | " + file.getAbsolutePath());
////////        }
////////    }
////////
////////    private void notifyOtaAvailable(String payloadUrl) {
////////        synchronized (callbacks) {
////////            for (IFotaS3Callback cb : callbacks) {
////////                try {
////////                    cb.onOtaAvailable(payloadUrl);
////////                } catch (RemoteException e) {
////////                    Log.e(TAG, "Error notifying callback", e);
////////                }
////////            }
////////        }
////////    }
////////
////////    private void scheduleRepeatingTask() {
////////        workerHandler.post(new Runnable() {
////////            @Override
////////            public void run() {
////////                performUploadDownload();
////////                workerHandler.postDelayed(this, INTERVAL_MS);
////////            }
////////        });
////////    }
////////
////////    private void performUploadDownload() {
////////        try {
////////            Log.i(TAG, "Performing upload/download cycle...");
////////            updateNotification("Uploading bike data...");
////////            UploadManager.processFiles(getApplicationContext());
////////
////////            updateNotification("Downloading files...");
////////            CloudDownloadManager.processDownloads(getApplicationContext());
////////
////////            updateNotification("Cycle complete - Next in 15 min");
////////        } catch (Exception e) {
////////            Log.e(TAG, "Error in upload/download cycle", e);
////////            updateNotification("Error - retrying in 15 minutes");
////////        }
////////    }
////////
////////    private void processOtaData(String version, String payloadUrl) {
////////        Log.i(TAG, "Processing OTA v" + version + " (" + payloadUrl + ")");
////////        updateNotification("Processing OTA v" + version + "...");
////////        try {
////////            Log.i(TAG, "Starting OTA download...");
////////            updateNotification("OTA v" + version + " processed");
////////        } catch (Exception e) {
////////            Log.e(TAG, "OTA processing failed", e);
////////            updateNotification("OTA failed");
////////        }
////////    }
////////
////////    private void createNotificationChannel() {
////////        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
////////        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
////////            NotificationChannel channel = new NotificationChannel(
////////                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
////////            channel.setDescription("Bike data upload/download with OTA binding");
////////            notificationManager.createNotificationChannel(channel);
////////        }
////////    }
////////
////////    private Notification createNotification(String message) {
////////        String title = "Bike Data Service";
////////        return new NotificationCompat.Builder(this, CHANNEL_ID)
////////                .setContentTitle(title)
////////                .setContentText(message)
////////                .setSmallIcon(android.R.drawable.stat_sys_upload)
////////                .setOngoing(true)
////////                .build();
////////    }
////////
////////    private void updateNotification(String message) {
////////        if (notificationManager != null) {
////////            notificationManager.notify(NOTIFICATION_ID, createNotification(message));
////////        }
////////    }
////////
////////    @Override
////////    public int onStartCommand(Intent intent, int flags, int startId) {
////////        Log.i(TAG, "onStartCommand triggered");
////////        updateNotification("Running periodic tasks...");
////////        return START_STICKY;
////////    }
////////
////////    @Override
////////    public void onDestroy() {
////////        Log.w(TAG, "DatabaseBackgroundService destroyed");
////////        if (workerHandler != null) workerHandler.removeCallbacksAndMessages(null);
////////        if (workerThread != null) workerThread.quitSafely();
////////        stopForeground(true);
////////        super.onDestroy();
////////    }
////////
////////    @Nullable
////////    @Override
////////    public IBinder onBind(Intent intent) {
////////        Log.i(TAG, "AIDL client binding to DatabaseBackgroundService");
////////        return binder;
////////    }
////////}
//////
//////
//////
//////package com.example.database.service;
//////
//////import android.app.Notification;
//////import android.app.NotificationChannel;
//////import android.app.NotificationManager;
//////import android.app.Service;
//////import android.content.ComponentName;
//////import android.content.Intent;
//////import android.content.ServiceConnection;
//////import android.os.Build;
//////import android.os.Handler;
//////import android.os.HandlerThread;
//////import android.os.IBinder;
//////import android.os.Looper;
//////import android.os.RemoteException;
//////import android.util.Log;
//////
//////import androidx.annotation.Nullable;
//////import androidx.core.app.NotificationCompat;
//////
//////import com.example.database.db.AppDatabase;
//////import com.example.database.db.FileDownloadDao;
//////import com.example.database.db.FileDownloadRecord;
//////import com.ultraviolette.fotaservice.IFotaS3Callback;
//////import com.ultraviolette.fotaservice.IFotaS3Events;
//////
//////import java.io.File;
//////import java.util.ArrayList;
//////import java.util.List;
//////import java.util.concurrent.Executors;
//////
//////public class DatabaseBackgroundService extends Service {
//////
//////    private static final String TAG = "DatabaseBackgroundService";
//////    private Handler mainHandler;
//////    private HandlerThread workerThread;
//////    private Handler workerHandler;
//////
//////    private static final int INTERVAL_MINUTES = 15;
//////    private static final long INTERVAL_MS = INTERVAL_MINUTES * 60 * 1000;
//////
//////    private static final int NOTIFICATION_ID = 1001;
//////    private static final String CHANNEL_ID = "bike_data_service";
//////    private static final String CHANNEL_NAME = "Bike Data Service";
//////
//////    private final List<IFotaS3Callback> callbacks = new ArrayList<>();
//////    private NotificationManager notificationManager;
//////
//////    // FOTA Service binding
//////    private int fotaBindAttempts = 0;
//////    private static final int MAX_RETRY_ATTEMPTS = 5;
//////    private static final long RETRY_INTERVAL_MS = 30_000; // retry every 30 seconds
//////
//////    @Override
//////    public void onCreate() {
//////        super.onCreate();
//////        Log.i(TAG, "DatabaseBackgroundService created");
//////
//////        createNotificationChannel();
//////        startForeground(NOTIFICATION_ID, createNotification("Service initializing..."));
//////
//////        mainHandler = new Handler(Looper.getMainLooper());
//////        workerThread = new HandlerThread("DatabaseWorkerThread");
//////        workerThread.start();
//////        workerHandler = new Handler(workerThread.getLooper());
//////
//////        scheduleRepeatingTask();
//////    }
//////
//////    private final IFotaS3Events.Stub binder = new IFotaS3Events.Stub() {
//////        @Override
//////        public void fotaDownloadRequest(String s3Key) throws RemoteException {
//////            Log.d(TAG, "Received FOTA download request for: " + s3Key);
//////            Log.i(TAG, "FOTA REQUEST: s3Key = " + s3Key);
//////
//////            String fileName = "fota.tar";
//////            triggerOtaDownloadDirectly(fileName);
//////            // REMOVED: notifyOtaAvailable("SUCCESS") — now called only after success
//////        }
//////
//////        @Override
//////        public void registerCallback(IFotaS3Callback callback) throws RemoteException {
//////            synchronized (callbacks) {
//////                if (!callbacks.contains(callback)) {
//////                    callbacks.add(callback);
//////                }
//////            }
//////            Log.d(TAG, "Callback registered");
//////        }
//////
//////        @Override
//////        public void unregisterCallback(IFotaS3Callback callback) throws RemoteException {
//////            synchronized (callbacks) {
//////                callbacks.remove(callback);
//////            }
//////            Log.d(TAG, "Callback unregistered");
//////        }
//////    };
//////
//////    private String extractFileName(String s3Key) {
//////        if (s3Key == null || s3Key.trim().isEmpty()) {
//////            Log.w(TAG, "s3Key is null or empty, using fallback");
//////            return "ota_fallback.bin";
//////        }
//////        int slash = s3Key.lastIndexOf('/');
//////        String name = slash >= 0 ? s3Key.substring(slash + 1) : s3Key;
//////        Log.d(TAG, "extractFileName: " + s3Key + " to " + name);
//////        return name;
//////    }
//////
//////    // UPDATED: Only this method changed — now supports RESUME for FOTA (uses your existing CloudDownloader with resume)
//////    private void triggerOtaDownloadDirectly(String fileName) {
//////        if (fileName == null || fileName.trim().isEmpty()) {
//////            Log.e(TAG, "triggerOtaDownloadDirectly: Invalid filename");
//////            notifyOtaAvailable("FAILED");
//////            return;
//////        }
//////
//////        Executors.newSingleThreadExecutor().execute(() -> {
//////            Log.i(TAG, "DIRECT OTA DOWNLOAD STARTED: " + fileName);
//////            updateNotification("Downloading OTA: " + fileName);
//////
//////            File downloadDir = new File("/data/vendor/uv_fota/fota");
//////            try {
//////                if (!downloadDir.exists()) {
//////                    boolean created = downloadDir.mkdirs();
//////                    downloadDir.setReadable(true, false);
//////                    downloadDir.setWritable(true, false);
//////                    Log.i(TAG, "Download dir created: " + downloadDir.getAbsolutePath() + " (created=" + created + ")");
//////                }
//////
//////                AppDatabase db = AppDatabase.getInstance(getApplicationContext());
//////                FileDownloadDao dao = db.fileDownloadDao();
//////                CloudDownloader downloader = new CloudDownloader(getApplicationContext(), dao);
//////
//////                File targetFile = new File(downloadDir, fileName);
//////                FileDownloadRecord record = dao.getRecordByFileName(fileName);
//////
//////                // RESUME: If file exists and matches DB → skip
//////                if (record != null && "completed".equals(record.status) && targetFile.exists() && targetFile.length() == record.fileSize) {
//////                    Log.i(TAG, "OTA ALREADY DOWNLOADED & VALID: " + fileName + " (" + targetFile.length() + " bytes)");
//////                    updateNotification("OTA ready: " + fileName);
//////                    notifyOtaReady(fileName);
//////                    notifyOtaAvailable("SUCCESS");
//////                    return;
//////                }
//////
//////                // RESUME: Prepare record with correct downloadedBytes
//////                if (record == null) {
//////                    record = new FileDownloadRecord(fileName, 0, "pending", null, System.currentTimeMillis(), 0);
//////                    dao.insert(record);
//////                } else {
//////                    record.status = "pending";
//////                    record.failureReason = null;
//////                    record.downloadedBytes = targetFile.exists() ? targetFile.length() : 0;  // ← RESUME SUPPORT
//////                    record.timestamp = System.currentTimeMillis();
//////                    dao.update(record);
//////                    Log.d(TAG, "Record reset to pending: " + fileName + " | Resuming from " + record.downloadedBytes + " bytes");
//////                }
//////
//////                Log.i(TAG, "DOWNLOADING OTA (WITH RESUME): " + fileName + " to " + targetFile.getAbsolutePath());
//////                String failureReason = downloader.downloadFile(record, targetFile);  // ← Uses your resume-capable downloader
//////
//////                if ("completed".equals(record.status) && targetFile.exists()) {
//////                    Log.i(TAG, "OTA DOWNLOAD SUCCESS: " + fileName + " (" + targetFile.length() + " bytes)");
//////                    updateNotification("OTA downloaded: " + fileName);
//////                    notifyOtaReady(fileName);
//////                    notifyOtaAvailable("SUCCESS");  // ONLY ON SUCCESS
//////                } else {
//////                    String reason = failureReason != null ? failureReason : "Unknown";
//////                    Log.w(TAG, "OTA DOWNLOAD FAILED: " + fileName + " | Reason: " + reason);
//////                    updateNotification("OTA failed: " + fileName + " (" + reason + ")");
//////
//////                    if ("FILE_NOT_FOUND".equals(reason)) {
//////                        Log.e(TAG, "FILE NOT FOUND ON S3 — Check Lambda key: " + fileName);
//////                    } else if ("NETWORK_ERROR".equals(reason)) {
//////                        Log.e(TAG, "NETWORK ISSUE — Retry in 30 min");
//////                    }
//////                    notifyOtaAvailable("FAILED");  // ON ANY FAILURE
//////                }
//////
//////            } catch (Exception e) {
//////                Log.e(TAG, "FATAL: OTA download crashed for " + fileName, e);
//////                updateNotification("OTA error: " + e.getMessage());
//////                notifyOtaAvailable("FAILED");
//////            }
//////        });
//////    }
//////
//////    private void notifyOtaReady(String fileName) {
//////        File file = new File("/data/vendor/uv_fota/fota", fileName);
//////        if (file.exists()) {
//////            Log.i(TAG, "OTA FILE READY: " + file.getAbsolutePath() + " (" + file.length() + " bytes)");
//////
//////            Intent intent = new Intent("com.ultraviolette.OTA_READY");
//////            intent.putExtra("file_path", file.getAbsolutePath());
//////            intent.putExtra("file_name", fileName);
//////            sendBroadcast(intent);
//////            Log.d(TAG, "Broadcast: com.ultraviolette.OTA_READY | " + file.getAbsolutePath());
//////        }
//////    }
//////
//////    private void notifyOtaAvailable(String payloadUrl) {
//////        synchronized (callbacks) {
//////            for (IFotaS3Callback cb : callbacks) {
//////                try {
//////                    cb.onOtaAvailable(payloadUrl);
//////                } catch (RemoteException e) {
//////                    Log.e(TAG, "Error notifying callback", e);
//////                }
//////            }
//////        }
//////    }
//////
//////    private void scheduleRepeatingTask() {
//////        workerHandler.post(new Runnable() {
//////            @Override
//////            public void run() {
//////                performUploadDownload();
//////                workerHandler.postDelayed(this, INTERVAL_MS);
//////            }
//////        });
//////    }
//////
//////    private void performUploadDownload() {
//////        try {
//////            Log.i(TAG, "Performing upload/download cycle...");
//////            updateNotification("Uploading bike data...");
//////            UploadManager.processFiles(getApplicationContext());
//////
//////            updateNotification("Downloading files...");
//////            CloudDownloadManager.processDownloads(getApplicationContext());
//////
//////            updateNotification("Cycle complete - Next in 15 min");
//////        } catch (Exception e) {
//////            Log.e(TAG, "Error in upload/download cycle", e);
//////            updateNotification("Error - retrying in 15 minutes");
//////        }
//////    }
//////
//////    private void processOtaData(String version, String payloadUrl) {
//////        Log.i(TAG, "Processing OTA v" + version + " (" + payloadUrl + ")");
//////        updateNotification("Processing OTA v" + version + "...");
//////        try {
//////            Log.i(TAG, "Starting OTA download...");
//////            updateNotification("OTA v" + version + " processed");
//////        } catch (Exception e) {
//////            Log.e(TAG, "OTA processing failed", e);
//////            updateNotification("OTA failed");
//////        }
//////    }
//////
//////    private void createNotificationChannel() {
//////        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
//////        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
//////            NotificationChannel channel = new NotificationChannel(
//////                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
//////            channel.setDescription("Bike data upload/download with OTA binding");
//////            notificationManager.createNotificationChannel(channel);
//////        }
//////    }
//////
//////    private Notification createNotification(String message) {
//////        String title = "Bike Data Service";
//////        return new NotificationCompat.Builder(this, CHANNEL_ID)
//////                .setContentTitle(title)
//////                .setContentText(message)
//////                .setSmallIcon(android.R.drawable.stat_sys_upload)
//////                .setOngoing(true)
//////                .build();
//////    }
//////
//////    private void updateNotification(String message) {
//////        if (notificationManager != null) {
//////            notificationManager.notify(NOTIFICATION_ID, createNotification(message));
//////        }
//////    }
//////
//////    @Override
//////    public int onStartCommand(Intent intent, int flags, int startId) {
//////        Log.i(TAG, "onStartCommand triggered");
//////        updateNotification("Running periodic tasks...");
//////        return START_STICKY;
//////    }
//////
//////    @Override
//////    public void onDestroy() {
//////        Log.w(TAG, "DatabaseBackgroundService destroyed");
//////        if (workerHandler != null) workerHandler.removeCallbacksAndMessages(null);
//////        if (workerThread != null) workerThread.quitSafely();
//////        stopForeground(true);
//////        super.onDestroy();
//////    }
//////
//////    @Nullable
//////    @Override
//////    public IBinder onBind(Intent intent) {
//////        Log.i(TAG, "AIDL client binding to DatabaseBackgroundService");
//////        return binder;
//////    }
//////}
////
////
////
////
////
////
////
////
////
////
////package com.example.database.service;
////
////import android.app.Notification;
////import android.app.NotificationChannel;
////import android.app.NotificationManager;
////import android.app.Service;
////import android.content.ComponentName;
////import android.content.Intent;
////import android.content.ServiceConnection;
////import android.os.Build;
////import android.os.Handler;
////import android.os.HandlerThread;
////import android.os.IBinder;
////import android.os.Looper;
////import android.os.RemoteException;
////import android.util.Log;
////
////import androidx.annotation.Nullable;
////import androidx.core.app.NotificationCompat;
////
////import com.example.database.db.AppDatabase;
////import com.example.database.db.FileDownloadDao;
////import com.example.database.db.FileDownloadRecord;
////import com.ultraviolette.fotaservice.IFotaS3Callback;
////import com.ultraviolette.fotaservice.IFotaS3Events;
////
////import java.io.File;
////import java.util.ArrayList;
////import java.util.List;
////import java.util.concurrent.Executors;
////
////public class DatabaseBackgroundService extends Service {
////
////    private static final String TAG = "DatabaseBackgroundService";
////    private Handler mainHandler;
////    private HandlerThread workerThread;
////    private Handler workerHandler;
////
////    private static final int INTERVAL_MINUTES = 15;
////    private static final long INTERVAL_MS = INTERVAL_MINUTES * 60 * 1000;
////
////    private static final int NOTIFICATION_ID = 1001;
////    private static final String CHANNEL_ID = "bike_data_service";
////    private static final String CHANNEL_NAME = "Bike Data Service";
////
////    private final List<IFotaS3Callback> callbacks = new ArrayList<>();
////    private NotificationManager notificationManager;
////
////    // FOTA Service binding
////    private int fotaBindAttempts = 0;
////    private static final int MAX_RETRY_ATTEMPTS = 5;
////    private static final long RETRY_INTERVAL_MS = 30_000; // retry every 30 seconds
////
////    @Override
////    public void onCreate() {
////        super.onCreate();
////        Log.i(TAG, "DatabaseBackgroundService created");
////
////        createNotificationChannel();
////        startForeground(NOTIFICATION_ID, createNotification("Service initializing..."));
////
////        mainHandler = new Handler(Looper.getMainLooper());
////        workerThread = new HandlerThread("DatabaseWorkerThread");
////        workerThread.start();
////        workerHandler = new Handler(workerThread.getLooper());
////
////        scheduleRepeatingTask();
////    }
////
////    private final IFotaS3Events.Stub binder = new IFotaS3Events.Stub() {
////        @Override
////        public void fotaDownloadRequest(String s3Key) throws RemoteException {
////            Log.d(TAG, "Received FOTA download request for: " + s3Key);
////            Log.i(TAG, "FOTA REQUEST: s3Key = " + s3Key);
////
////            // FIXED: Now passes the actual s3Key from server instead of hardcoded "fota.tar"
////            triggerOtaDownloadDirectly(s3Key);
////            // REMOVED: notifyOtaAvailable("SUCCESS") — now called only after success
////        }
////
////        @Override
////        public void registerCallback(IFotaS3Callback callback) throws RemoteException {
////            synchronized (callbacks) {
////                if (!callbacks.contains(callback)) {
////                    callbacks.add(callback);
////                }
////            }
////            Log.d(TAG, "Callback registered");
////        }
////
////        @Override
////        public void unregisterCallback(IFotaS3Callback callback) throws RemoteException {
////            synchronized (callbacks) {
////                callbacks.remove(callback);
////            }
////            Log.d(TAG, "Callback unregistered");
////        }
////    };
////
////    private String extractFileName(String s3Key) {
////        if (s3Key == null || s3Key.trim().isEmpty()) {
////            Log.w(TAG, "s3Key is null or empty, using fallback");
////            return "ota_fallback.bin";
////        }
////        int slash = s3Key.lastIndexOf('/');
////        String name = slash >= 0 ? s3Key.substring(slash + 1) : s3Key;
////        Log.d(TAG, "extractFileName: " + s3Key + " to " + name);
////        return name;
////    }
////
////    // FULLY FIXED: Now downloads real file using real s3Key and renames to fota.tar
////    // FULLY FIXED: Now downloads real file using real s3Key and renames to fota.tar
////    private void triggerOtaDownloadDirectly(String s3KeyFromServer) {
////        if (s3KeyFromServer == null || s3KeyFromServer.trim().isEmpty()) {
////            Log.e(TAG, "triggerOtaDownloadDirectly: Invalid or empty s3Key");
////            notifyOtaAvailable("FAILED");
////            return;
////        }
////
////        String displayName = extractFileName(s3KeyFromServer);
////        Log.i(TAG, "DIRECT OTA DOWNLOAD STARTED → S3 Key: " + s3KeyFromServer + " → Display: " + displayName);
////
////        Executors.newSingleThreadExecutor().execute(() -> {
////            updateNotification("Downloading OTA: " + displayName);
////
////            File downloadDir = new File("/data/vendor/uv_fota/fota");
////            try {
////                if (!downloadDir.exists()) {
////                    boolean created = downloadDir.mkdirs();
////                    downloadDir.setReadable(true, false);
////                    downloadDir.setWritable(true, false);
////                    Log.i(TAG, "Download dir created: " + downloadDir.getAbsolutePath() + " (created=" + created + ")");
////                }
////
////                File finalFotaFile = new File(downloadDir, "fota.tar");
////                File tempFile = new File(downloadDir, displayName);  // temporary real name
////
////                AppDatabase db = AppDatabase.getInstance(getApplicationContext());
////                FileDownloadDao dao = db.fileDownloadDao();
////                CloudDownloader downloader = new CloudDownloader(getApplicationContext(), dao);
////
////                // Use FULL s3Key as identifier in DB
////                FileDownloadRecord record = dao.getRecordByFileName(s3KeyFromServer);
////
////                // If already completed and fota.tar exists → skip
////                if (finalFotaFile.exists() && record != null && "completed".equals(record.status)) {
////                    Log.i(TAG, "OTA ALREADY DOWNLOADED & READY: fota.tar");
////                    updateNotification("OTA ready: fota.tar");
////                    notifyOtaReady("fota.tar");
////                    notifyOtaAvailable("SUCCESS");
////                    return;
////                }
////
////                // Prepare or reset record
////                if (record == null) {
////                    record = new FileDownloadRecord(s3KeyFromServer, 0, "pending", null, System.currentTimeMillis(), 0);
////                    dao.insert(record);
////                } else {
////                    record.status = "pending";
////                    record.failureReason = null;
////                    record.downloadedBytes = tempFile.exists()() ? tempFile.length() : 0;
////                    record.timestamp = System.currentTimeMillis();
////                    dao.update(record);
////                }
////
////                Log.i(TAG, "DOWNLOADING OTA (WITH RESUME): " + s3KeyFromServer + " → " + tempFile.getAbsolutePath());
////                String failureReason = downloader.downloadFile(record, tempFile);
////
////                if ("completed".equals(record.status) && tempFile.exists()) {
////                    // RENAME OR COPY TO fota.tar
////                    if (tempFile.renameTo(finalFotaFile)) {
////                        Log.i(TAG, "OTA SUCCESS: " + displayName + " → renamed to fota.tar");
////                    } else {
////                        Log.w(TAG, "Rename failed, copying manually...");
////                        // FIXED: Manual copy instead of copyTo()
////                        Files.copy(tempFile.toPath(), finalFotaFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
////                        tempFile.delete();
////                        Log.i(TAG, "OTA SUCCESS: " + displayName + " → copied to fota.tar");
////                    }
////
////                    updateNotification("OTA downloaded: fota.tar");
////                    notifyOtaReady("fota.tar");
////                    notifyOtaAvailable("SUCCESS");
////                } else {
////                    String reason = failureReason != null ? failureReason : "Unknown";
////                    Log.w(TAG, "OTA DOWNLOAD FAILED: " + displayName + " | Reason: " + reason);
////                    updateNotification("OTA failed: " + displayName + " (" + reason + ")");
////
////                    if ("FILE_NOT_FOUND".equals(reason)) {
////                        Log.e(TAG, "FILE NOT FOUND ON S3 — Check key: " + s3KeyFromServer);
////                    } else if ("NETWORK_ERROR".equals(reason)) {
////                        Log.e(TAG, "NETWORK ISSUE — Retry in 30 min");
////                    }
////                    notifyOtaAvailable("FAILED");
////                }
////
////            } catch (Exception e) {
////                Log.e(TAG, "FATAL: OTA download crashed for " + s3KeyFromServer, e);
////                updateNotification("OTA error: " + e.getMessage());
////                notifyOtaAvailable("FAILED");
////            }
////        });
////    }
////
////    private void notifyOtaReady(String fileName) {
////        File file = new File("/data/vendor/uv_fota/fota", fileName);
////        if (file.exists()) {
////            Log.i(TAG, "OTA FILE READY: " + file.getAbsolutePath() + " (" + file.length() + " bytes)");
////
////            Intent intent = new Intent("com.ultraviolette.OTA_READY");
////            intent.putExtra("file_path", file.getAbsolutePath());
////            intent.putExtra("file_name", fileName);
////            sendBroadcast(intent);
////            Log.d(TAG, "Broadcast: com.ultraviolette.OTA_READY | " + file.getAbsolutePath());
////        }
////    }
////
////    private void notifyOtaAvailable(String payloadUrl) {
////        synchronized (callbacks) {
////            for (IFotaS3Callback cb : callbacks) {
////                try {
////                    cb.onOtaAvailable(payloadUrl);
////                } catch (RemoteException e) {
////                    Log.e(TAG, "Error notifying callback", e);
////                }
////            }
////        }
////    }
////
////    private void scheduleRepeatingTask() {
////        workerHandler.post(new Runnable() {
////            @Override
////            public void run() {
////                performUploadDownload();
////                workerHandler.postDelayed(this, INTERVAL_MS);
////            }
////        });
////    }
////
////    private void performUploadDownload() {
////        try {
////            Log.i(TAG, "Performing upload/download cycle...");
////            updateNotification("Uploading bike data...");
////            UploadManager.processFiles(getApplicationContext());
////
////            updateNotification("Downloading files...");
////            CloudDownloadManager.processDownloads(getApplicationContext());
////
////            updateNotification("Cycle complete - Next in 15 min");
////        } catch (Exception e) {
////            Log.e(TAG, "Error in upload/download cycle", e);
////            updateNotification("Error - retrying in 15 minutes");
////        }
////    }
////
////    private void processOtaData(String version, String payloadUrl) {
////        Log.i(TAG, "Processing OTA v" + version + " (" + payloadUrl + ")");
////        updateNotification("Processing OTA v" + version + "...");
////        try {
////            Log.i(TAG, "Starting OTA download...");
////            updateNotification("OTA v" + version + " processed");
////        } catch (Exception e) {
////            Log.e(TAG, "OTA processing failed", e);
////            updateNotification("OTA failed");
////        }
////    }
////
////    private void createNotificationChannel() {
////        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
////        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
////            NotificationChannel channel = new NotificationChannel(
////                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
////            channel.setDescription("Bike data upload/download with OTA binding");
////            notificationManager.createNotificationChannel(channel);
////        }
////    }
////
////    private Notification createNotification(String message) {
////        String title = "Bike Data Service";
////        return new NotificationCompat.Builder(this, CHANNEL_ID)
////                .setContentTitle(title)
////                .setContentText(message)
////                .setSmallIcon(android.R.drawable.stat_sys_upload)
////                .setOngoing(true)
////                .build();
////    }
////
////    private void updateNotification(String message) {
////        if (notificationManager != null) {
////            notificationManager.notify(NOTIFICATION_ID, createNotification(message));
////        }
////    }
////
////    @Override
////    public int onStartCommand(Intent intent, int flags, int startId) {
////        Log.i(TAG, "onStartCommand triggered");
////        updateNotification("Running periodic tasks...");
////        return START_STICKY;
////    }
////
////    @Override
////    public void onDestroy() {
////        Log.w(TAG, "DatabaseBackgroundService destroyed");
////        if (workerHandler != null) workerHandler.removeCallbacksAndMessages(null);
////        if (workerThread != null) workerThread.quitSafely();
////        stopForeground(true);
////        super.onDestroy();
////    }
////
////    @Nullable
////    @Override
////    public IBinder onBind(Intent intent) {
////        Log.i(TAG, "AIDL client binding to DatabaseBackgroundService");
////        return binder;
////    }
////}
//
//
//
//
//
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
//import com.example.database.db.AppDatabase;
//import com.example.database.db.FileDownloadDao;
//import com.example.database.db.FileDownloadRecord;
//import com.ultraviolette.fotaservice.IFotaS3Callback;
//import com.ultraviolette.fotaservice.IFotaS3Events;
//
//import java.io.File;
//import java.util.ArrayList;
//import java.util.List;
//import java.util.concurrent.Executors;
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
//    // FOTA Service binding
//    private int fotaBindAttempts = 0;
//    private static final int MAX_RETRY_ATTEMPTS = 5;
//    private static final long RETRY_INTERVAL_MS = 30_000; // retry every 30 seconds
//
//    @Override
//    public void onCreate() {
//        super.onCreate();
//        Log.i(TAG, "DatabaseBackgroundService created");
//
//        createNotificationChannel();
//        startForeground(NOTIFICATION_ID, createNotification("Service initializing..."));
//
//        mainHandler = new Handler(Looper.getMainLooper());
//        workerThread = new HandlerThread("DatabaseWorkerThread");
//        workerThread.start();
//        workerHandler = new Handler(workerThread.getLooper());
//
//        scheduleRepeatingTask();
//    }
//
//    private final IFotaS3Events.Stub binder = new IFotaS3Events.Stub() {
//        @Override
//        public void fotaDownloadRequest(String fileName) throws RemoteException {
//            Log.d(TAG, "Received FOTA download request for: " + fileName);
//            Log.i(TAG, "FOTA REQUEST: s3Key = " + fileName);
//
//            String s3fileName = "fota" + fileName.replaceFirst("^/*", "/");
//            triggerOtaDownloadDirectly(s3fileName);
//            // REMOVED: notifyOtaAvailable("SUCCESS") — now called only after success
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
//    private String extractFileName(String s3Key) {
//        if (s3Key == null || s3Key.trim().isEmpty()) {
//            Log.w(TAG, "s3Key is null or empty, using fallback");
//            return "ota_fallback.bin";
//        }
//        int slash = s3Key.lastIndexOf('/');
//        String name = slash >= 0 ? s3Key.substring(slash + 1) : s3Key;
//        Log.d(TAG, "extractFileName: " + s3Key + " to " + name);
//        return name;
//    }
//
//    private void triggerOtaDownloadDirectly(String fileName) {
//        if (fileName == null || fileName.trim().isEmpty()) {
//            Log.e(TAG, "triggerOtaDownloadDirectly: Invalid filename");
//            notifyOtaAvailable("FAILED");
//            return;
//        }
//
//        Executors.newSingleThreadExecutor().execute(() -> {
//            Log.i(TAG, "DIRECT OTA DOWNLOAD STARTED: " + fileName);
//            updateNotification("Preparing OTA download...");
//
//            File downloadDir = new File("/data/vendor/uv_fota/fota");
//            File targetFile = new File(downloadDir, "fota.tar");
//
//            try {
//                if (!downloadDir.exists()) {
//                    boolean created = downloadDir.mkdirs();
//                    downloadDir.setReadable(true, false);
//                    downloadDir.setWritable(true, false);
//                    Log.i(TAG, "Download dir created: " + downloadDir.getAbsolutePath() + " (created=" + created + ")");
//                }
//
//                // DELETE PREVIOUS fota.tar BEFORE NEW DOWNLOAD
//                if (targetFile.exists()) {
//                    boolean deleted = targetFile.delete();
//                    Log.i(TAG, "DELETED previous OTA file: " + targetFile.getAbsolutePath() + " (deleted=" + deleted + ")");
//                    if (!deleted) {
//                        Log.w(TAG, "Failed to delete previous fota.tar — may cause issues");
//                    }
//                }
//
//                AppDatabase db = AppDatabase.getInstance(getApplicationContext());
//                FileDownloadDao dao = db.fileDownloadDao();
//                CloudDownloader downloader = new CloudDownloader(getApplicationContext(), dao);
//
//                FileDownloadRecord record = dao.getRecordByFileName(fileName);
//
//                if (record != null && "completed".equals(record.status) && targetFile.exists()) {
//                    Log.i(TAG, "OTA ALREADY DOWNLOADED: fota.tar (" + targetFile.length() + " bytes)");
//                    updateNotification("OTA ready: fota.tar");
//                    notifyOtaReady();
//                    notifyOtaAvailable("SUCCESS");
//                    return;
//                }
//
//                if (record == null) {
//                    record = new FileDownloadRecord(fileName, 0, "pending", null, System.currentTimeMillis(), 0);
//                    long id = dao.insert(record);
//                    Log.d(TAG, "New download record: id=" + id + ", file=" + fileName);
//                } else {
//                    record.status = "pending";
//                    record.failureReason = null;
//                    record.timestamp = System.currentTimeMillis();
//                    dao.update(record);
//                    Log.d(TAG, "Record reset to pending: " + fileName);
//                }
//
//                Log.i(TAG, "DOWNLOADING OTA: " + fileName + " to " + targetFile.getAbsolutePath());
//                String failureReason = downloader.downloadFile(record, targetFile);
//
//                if ("completed".equals(record.status) && targetFile.exists()) {
//                    Log.i(TAG, "OTA DOWNLOAD SUCCESS: fota.tar (" + targetFile.length() + " bytes)");
//                    updateNotification("OTA downloaded: fota.tar");
//                    notifyOtaReady();
//                    notifyOtaAvailable("SUCCESS");
//                } else {
//                    String reason = failureReason != null ? failureReason : "Unknown";
//                    Log.w(TAG, "OTA DOWNLOAD FAILED: " + fileName + " | Reason: " + reason);
//                    updateNotification("OTA failed: fota.tar (" + reason + ")");
//
//                    if ("FILE_NOT_FOUND".equals(reason)) {
//                        Log.e(TAG, "FILE NOT FOUND ON S3 — Check Lambda key: " + fileName);
//                    } else if ("NETWORK_ERROR".equals(reason)) {
//                        Log.e(TAG, "NETWORK ISSUE — Retry in 30 min");
//                    }
//                    notifyOtaAvailable("FAILED");
//                }
//
//            } catch (Exception e) {
//                Log.e(TAG, "FATAL: OTA download crashed for " + fileName, e);
//                updateNotification("OTA error: " + e.getMessage());
//                notifyOtaAvailable("FAILED");
//            }
//        });
//    }
//
//    private void notifyOtaReady() {
//        File file = new File("/data/vendor/uv_fota/fota/fota.tar");
//        if (file.exists()) {
//            Log.i(TAG, "OTA FILE READY: " + file.getAbsolutePath() + " (" + file.length() + " bytes)");
//
//            Intent intent = new Intent("com.ultraviolette.OTA_READY");
//            intent.putExtra("file_path", file.getAbsolutePath());
//            intent.putExtra("file_name", "fota.tar");
//            sendBroadcast(intent);
//            Log.d(TAG, "Broadcast: com.ultraviolette.OTA_READY | " + file.getAbsolutePath());
//        }
//    }
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
//            Log.i(TAG, "Performing upload/download cycle...");
//            updateNotification("Uploading bike data...");
//            UploadManager.processFiles(getApplicationContext());
//
//            updateNotification("Downloading files...");
//            CloudDownloadManager.processDownloads(getApplicationContext());
//
//            updateNotification("Cycle complete - Next in 15 min");
//        } catch (Exception e) {
//            Log.e(TAG, "Error in upload/download cycle", e);
//            updateNotification("Error - retrying in 15 minutes");
//        }
//    }
//
//    private void processOtaData(String version, String payloadUrl) {
//        Log.i(TAG, "Processing OTA v" + version + " (" + payloadUrl + ")");
//        updateNotification("Processing OTA v" + version + "...");
//        try {
//            Log.i(TAG, "Starting OTA download...");
//            updateNotification("OTA v" + version + " processed");
//        } catch (Exception e) {
//            Log.e(TAG, "OTA processing failed", e);
//            updateNotification("OTA failed");
//        }
//    }
//
//    private void createNotificationChannel() {
//        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
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
//        Log.i(TAG, "onStartCommand triggered");
//        updateNotification("Running periodic tasks...");
//        return START_STICKY;
//    }
//
//    @Override
//    public void onDestroy() {
//        Log.w(TAG, "DatabaseBackgroundService destroyed");
//        if (workerHandler != null) workerHandler.removeCallbacksAndMessages(null);
//        if (workerThread != null) workerThread.quitSafely();
//        stopForeground(true);
//        super.onDestroy();
//    }
//
//    @Nullable
//    @Override
//    public IBinder onBind(Intent intent) {
//        Log.i(TAG, "AIDL client binding to DatabaseBackgroundService");
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
import android.content.Context;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.database.db.AppDatabase;
import com.example.database.db.FileDownloadDao;
import com.example.database.db.FileDownloadRecord;
import com.ultraviolette.fotaservice.IFotaS3Callback;
import com.ultraviolette.fotaservice.IFotaS3Events;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

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

    // INSTANT RESUME ON WIFI RETURN
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "DatabaseBackgroundService created");

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification("Service initializing..."));

        mainHandler = new Handler(Looper.getMainLooper());
        workerThread = new HandlerThread("DatabaseWorkerThread");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());

        scheduleRepeatingTask();

        // INSTANT OTA RESUME WHEN WIFI COMES BACK (NO 15 MIN WAIT)
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                Log.w(TAG, "WIFI CONNECTED → INSTANT OTA RESUME CHECK");
                workerHandler.post(() -> {
                    AppDatabase db = AppDatabase.getInstance(getApplicationContext());
                    FileDownloadDao dao = db.fileDownloadDao();
                    List<FileDownloadRecord> otaList = dao.getRecordsByPrefix("fota/");
                    for (FileDownloadRecord r : otaList) {
                        if (!"completed".equals(r.status)) {
                            File target = new File("/data/vendor/uv_fota/fota", extractFileName(r.fileName));
                            Log.w(TAG, "INSTANT RESUME OTA: " + r.fileName + " (" + formatBytes(r.downloadedBytes) + ")");
                            new CloudDownloader(getApplicationContext(), dao).downloadFile(r, target);
                        }
                    }
                });
            }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
        } else {
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build();
            connectivityManager.registerNetworkCallback(request, networkCallback);
        }
    }

    private final IFotaS3Events.Stub binder = new IFotaS3Events.Stub() {
        @Override
        public void fotaDownloadRequest(String fileName) throws RemoteException {
            Log.d(TAG, "Received FOTA download request for: " + fileName);
            Log.i(TAG, "FOTA REQUEST: s3Key = " + fileName);

            String s3fileName = fileName.startsWith("fota/") ? fileName : "fota/" + fileName;
            triggerOtaDownloadDirectly(s3fileName);
            // REMOVED: notifyOtaAvailable("SUCCESS") — now called only after success
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
            Log.w(TAG, "s3Key is null or empty, using fallback");
            return "ota_fallback.bin";
        }
        int slash = s3Key.lastIndexOf('/');
        String name = slash >= 0 ? s3Key.substring(slash + 1) : s3Key;
        Log.d(TAG, "extractFileName: " + s3Key + " to " + name);
        return name;
    }

    private void triggerOtaDownloadDirectly(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            Log.e(TAG, "triggerOtaDownloadDirectly: Invalid filename");
            notifyOtaAvailable("FAILED");
            return;
        }

        Executors.newSingleThreadExecutor().execute(() -> {
            Log.i(TAG, "DIRECT OTA DOWNLOAD STARTED: " + fileName);
            updateNotification("Preparing OTA download...");

            File downloadDir = new File("/data/vendor/uv_fota/fota");
            String realName = extractFileName(fileName);
            File partialFile = new File(downloadDir, realName);        // during download
            File finalFile = new File(downloadDir, "fota.tar");        // final name

            try {
                if (!downloadDir.exists()) {
                    boolean created = downloadDir.mkdirs();
                    downloadDir.setReadable(true, false);
                    downloadDir.setWritable(true, false);
                    Log.i(TAG, "Download dir created: " + downloadDir.getAbsolutePath() + " (created=" + created + ")");
                }

                AppDatabase db = AppDatabase.getInstance(getApplicationContext());
                FileDownloadDao dao = db.fileDownloadDao();
                CloudDownloader downloader = new CloudDownloader(getApplicationContext(), dao);

                FileDownloadRecord record = dao.getRecordByFileName(fileName);

                // AUTO DELETE OLD fota.tar + partial → ALWAYS FRESH DOWNLOAD
                if (finalFile.exists()) {
                    boolean deleted = finalFile.delete();
                    Log.w(TAG, "AUTO DELETED OLD fota.tar (success=" + deleted + ") → STARTING FRESH DOWNLOAD");
                }
                if (partialFile.exists()) {
                    boolean deleted = partialFile.delete();
                    Log.w(TAG, "DELETED OLD PARTIAL: " + realName + " (success=" + deleted + ")");
                }

                long existing = 0;

                if (record == null) {
                    record = new FileDownloadRecord(fileName, existing, "pending", null, System.currentTimeMillis(), 0);
                    dao.insert(record);
                } else {
                    record.status = "pending";
                    record.downloadedBytes = existing;
                    record.timestamp = System.currentTimeMillis();
                    dao.update(record);
                }

                Log.i(TAG, "DOWNLOADING FRESH OTA: " + fileName + " → saving as " + realName);
                String failureReason = downloader.downloadFile(record, partialFile);

                if ("completed".equals(record.status)) {
                    if (finalFile.exists()) finalFile.delete();
                    boolean renamed = partialFile.renameTo(finalFile);
                    Log.w(TAG, "RENAMED " + realName + " → fota.tar (success=" + renamed + ")");

                    Log.i(TAG, "OTA DOWNLOAD SUCCESS → FINAL FILE: fota.tar (" + finalFile.length() + " bytes)");
                    updateNotification("OTA downloaded: fota.tar");
                    notifyOtaReady();
                    notifyOtaAvailable("SUCCESS");
                } else {
                    String reason = failureReason != null ? failureReason : "Unknown";
                    Log.w(TAG, "OTA DOWNLOAD FAILED: " + fileName + " | Reason: " + reason);
                    updateNotification("OTA failed: " + realName + " (" + reason + ")");
                    notifyOtaAvailable("FAILED");
                }

            } catch (Exception e) {
                Log.e(TAG, "FATAL: OTA download crashed for " + fileName, e);
                updateNotification("OTA error: " + e.getMessage());
                notifyOtaAvailable("FAILED");
            }
        });
    }

    private void notifyOtaReady() {
        File file = new File("/data/vendor/uv_fota/fota/fota.tar");
        if (file.exists()) {
            Log.i(TAG, "OTA FILE READY: " + file.getAbsolutePath() + " (" + file.length() + " bytes)");

            Intent intent = new Intent("com.ultraviolette.OTA_READY");
            intent.putExtra("file_path", file.getAbsolutePath());
            intent.putExtra("file_name", "fota.tar");
            sendBroadcast(intent);
            Log.d(TAG, "Broadcast: com.ultraviolette.OTA_READY | " + file.getAbsolutePath());
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
            Log.i(TAG, "Performing upload/download cycle...");
            updateNotification("Uploading bike data...");
            UploadManager.processFiles(getApplicationContext());

            updateNotification("Downloading files...");
            CloudDownloadManager.processDownloads(getApplicationContext());

            updateNotification("Cycle complete - Next in 15 min");
        } catch (Exception e) {
            Log.e(TAG, "Error in upload/download cycle", e);
            updateNotification("Error - retrying in 15 minutes");
        }
    }

    private void processOtaData(String version, String payloadUrl) {
        Log.i(TAG, "Processing OTA v" + version + " (" + payloadUrl + ")");
        updateNotification("Processing OTA v" + version + "...");
        try {
            Log.i(TAG, "Starting OTA download...");
            updateNotification("OTA v" + version + " processed");
        } catch (Exception e) {
            Log.e(TAG, "OTA processing failed", e);
            updateNotification("OTA failed");
        }
    }

    private void createNotificationChannel() {
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
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

    private String formatBytes(long bytes) {
        if (bytes >= 1024 * 1024 * 1024) {
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        } else {
            return String.format("%.2f MB", bytes / (1024.0 * 1024));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand triggered");
        updateNotification("Running periodic tasks...");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.w(TAG, "DatabaseBackgroundService destroyed");
        if (workerHandler != null) workerHandler.removeCallbacksAndMessages(null);
        if (workerThread != null) workerThread.quitSafely();
        if (connectivityManager != null && networkCallback != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        }
        stopForeground(true);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "AIDL client binding to DatabaseBackgroundService");
        return binder;
    }
}