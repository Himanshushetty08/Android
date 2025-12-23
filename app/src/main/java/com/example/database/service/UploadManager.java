
package com.example.database.service;
import com.example.database.utils.DeviceSession;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;


import android.content.Context;
import android.util.Log;
import com.example.database.db.AppDatabase;
import com.example.database.db.FileUploadDao;
import com.example.database.db.FileUploadRecord;
import com.example.database.service.network.NetworkSelector;
import com.example.database.utils.NetworkUtils;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.util.List;
import java.util.ArrayList;


public class UploadManager {

    private static final String TAG = "UploadManager";
    private static final String LOGS_SOURCE_DIR = "/data/system/udp_logs";
    private static final String QUEUE_DIR = "upload_queue";

    //private static final String LAMBDA_URL = "https://bpfsuu5xvj.execute-api.ap-south-1.amazonaws.com/default/s3uploadurlcreatorv1-dev-getPreSignedURLToPutToS3-dev";

    private static final String LAMBDA_URL ="https://bpfsuu5xvj.execute-api.ap-south-1.amazonaws.com/default/s3uploadurlcreatorv1-dev-getPreSignedURLToPutToS3";

    private static OkHttpClient currentHttpClient = createLambdaClient();

    // ********************************************************************************


    // ========================================================================
    // NEW: EMERGENCY UPLOAD — BYPASSES WIFI CHECK — WORKS ON LTE/5G/WiFi
    // ========================================================================
    public static void processFilesForcedEmergency(Context context) {
        Log.w(TAG, "EMERGENCY UPLOAD TRIGGERED — BYPASSING WIFI CHECK — USING ANY NETWORK");

        NetworkSelector.Result bestNetwork = NetworkSelector.getBestNetwork(context);

        if (bestNetwork == null || bestNetwork.network == null) {
            Log.e(TAG, "No network available — cannot perform emergency upload");
            return;
        }

        Log.w(TAG, "EMERGENCY USING BEST NETWORK → " + bestNetwork);

        OkHttpClient emergencyClient = currentHttpClient.newBuilder()
                .socketFactory(bestNetwork.network.getSocketFactory())
                .build();

        OkHttpClient previous = currentHttpClient;
        currentHttpClient = emergencyClient;

        try {
            copyReadableFiles(context);
            uploadFromQueue(context);
            retryFailedUploads(context);

            Log.w(TAG, "EMERGENCY UPLOAD COMPLETED SUCCESSFULLY — ALL LOGS SENT");
        } catch (Exception e) {
            Log.e(TAG, "Emergency upload failed completely", e);
        } finally {
            currentHttpClient = previous;
        }
    }

//    public static void processFiles(Context context) {
//        Executors.newSingleThreadExecutor().execute(() -> {
//            Log.i(TAG, "UploadManager: Starting upload process...");
//
//            if (!NetworkUtils.isWifiConnected(context)) {
//                Log.i(TAG, "Wi-Fi not connected, upload aborted");
//                return;
//            }
//
//            try {
//                copyReadableFiles(context);
//                uploadFromQueue(context);
//                retryFailedUploads(context);
//                Log.i(TAG, "Upload process completed");
//
//            } catch (Exception e) {
//                Log.e(TAG, "Error in upload process", e);
//            }
//        });
//    }

// selects the whatever network is availaible if both are there then it will compare and pick the network

    public static void processFiles(Context context) {
        Executors.newSingleThreadExecutor().execute(() -> {
            Log.i(TAG, "UploadManager: Starting upload process (ANY NETWORK)");

            NetworkSelector.Result bestNetwork = NetworkSelector.getBestNetwork(context);

            if (bestNetwork == null || bestNetwork.network == null) {
                Log.e(TAG, "No network available — upload aborted");
                return;
            }

            OkHttpClient networkClient = currentHttpClient.newBuilder()
                    .socketFactory(bestNetwork.network.getSocketFactory())
                    .build();

            OkHttpClient previous = currentHttpClient;
            currentHttpClient = networkClient;

            try {
                copyReadableFiles(context);
                uploadFromQueue(context);
                retryFailedUploads(context);
                Log.i(TAG, "Upload process completed on " + bestNetwork);
            } catch (Exception e) {
                Log.e(TAG, "Error in upload process", e);
            } finally {
                currentHttpClient = previous;
            }
        });
    }



    //    private static void copyReadableFiles(Context context) {
//        File logsSourceDir = new File(LOGS_SOURCE_DIR);
//        File queueDir = new File(context.getFilesDir(), QUEUE_DIR);
//
//        if (!queueDir.exists()) queueDir.mkdirs();
//
//        try {
//            File[] sourceFiles = logsSourceDir.listFiles();
//            if (sourceFiles == null) {
//                Log.w(TAG, "Cannot access " + LOGS_SOURCE_DIR + " (check folder exists + permissions)");
//                return;
//            }
//
//            AppDatabase db = AppDatabase.getInstance(context);
//            FileUploadDao dao = db.fileUploadDao();
//
//            int copied = 0, skipped = 0, errors = 0;
//
//            for (File sourceFile : sourceFiles) {
//                if (!sourceFile.isFile() || !sourceFile.canRead()) continue;
//
//                File queueFile = new File(queueDir, sourceFile.getName());
//                if (queueFile.exists()) { skipped++; continue; }
//
//                FileUploadRecord existing = dao.getRecordByFileName(sourceFile.getName());
//                if (existing != null) { skipped++; continue; }
//
//                try {
//                    copyFile(sourceFile, queueFile);
//                    copied++;
//                    Log.d(TAG, "Copied to queue: " + sourceFile.getName());
//                } catch (IOException e) {
//                    errors++;
//                    Log.e(TAG, "Copy failed: " + sourceFile.getName(), e);
//                }
//            }
//
//            Log.i(TAG, String.format("File copy summary - Copied: %d, Skipped: %d, Errors: %d", copied, skipped, errors));
//
//        } catch (Exception e) {
//            Log.e(TAG, "Error copying files from " + LOGS_SOURCE_DIR, e);
//        }
//    }
private static void collectFilesRecursively(File dir, List<File> out) {
    File[] files = dir.listFiles();
    if (files == null) return;

    for (File f : files) {
        if (f.isDirectory()) {
            collectFilesRecursively(f, out);
        } else {
            out.add(f);
        }
    }
}

    // ---------- UPDATED COPY METHOD ----------
    private static void copyReadableFiles(Context context) {
        File logsSourceDir = new File(LOGS_SOURCE_DIR);
        File queueDir = new File(context.getFilesDir(), QUEUE_DIR);

        if (!queueDir.exists()) queueDir.mkdirs();

        try {
            List<File> sourceFiles = new ArrayList<>();
            collectFilesRecursively(logsSourceDir, sourceFiles);

            if (sourceFiles.isEmpty()) {
                Log.w(TAG, "No files found under " + LOGS_SOURCE_DIR);
                return;
            }

            AppDatabase db = AppDatabase.getInstance(context);
            FileUploadDao dao = db.fileUploadDao();

            int copied = 0, skipped = 0, errors = 0;

            for (File sourceFile : sourceFiles) {

                // ✅ only allow required file types
                String name = sourceFile.getName().toLowerCase();
                if (!(name.endsWith(".bin") || name.endsWith(".gz") || name.endsWith(".txt"))) {
                    Log.i(TAG, "Skipping " + sourceFile.getName() + " (not supported)");
                   // skipped++;
                    continue;
                }

                if (!sourceFile.isFile() || ! sourceFile.canRead()) {
                    Log.i(TAG, "Skipping " + sourceFile.getName() + " (not readable)" +  sourceFile.canRead()  + !sourceFile.isFile());
                    skipped++;
                    continue;
                }

               File queueFile = new File(queueDir, sourceFile.getName());
//                String newFileName = replaceImeiIfPresent(sourceFile.getName());
//
//                File queueFile = new File(queueDir, newFileName);


                if (queueFile.exists()) {
                    Log.i(TAG, "Skipping  queueFile.exists" + sourceFile.getName() + " (not supported)");
                    skipped++;
                    continue;
                }

                FileUploadRecord existing = dao.getRecordByFileName(sourceFile.getName());
                if (existing != null) {
                    Log.i(TAG, "Skipping  existing.exists" + sourceFile.getName() + " (not supported)");
                    skipped++;
                    continue;
                }

                try {
                    copyFile(sourceFile, queueFile);
                    copied++;
                    Log.d(TAG, "Copied to queue: " + sourceFile.getAbsolutePath());
                } catch (IOException e) {
                    errors++;
                    Log.e(TAG, "Copy failed: " + sourceFile.getAbsolutePath(), e);
                }
            }

            Log.i(TAG, String.format(
                    "File copy summary - Copied: %d, Skipped: %d, Errors: %d",
                    copied, skipped, errors
            ));

        } catch (Exception e) {
            Log.e(TAG, "Error copying files from " + LOGS_SOURCE_DIR, e);
        }
    }
    private static void uploadFromQueue(Context context) {
        File queueDir = new File(context.getFilesDir(), QUEUE_DIR);
        File[] queueFiles = queueDir.listFiles();

        if (queueFiles == null || queueFiles.length == 0) {
            Log.d(TAG, "Upload queue is empty");
            return;
        }

        AppDatabase db = AppDatabase.getInstance(context);
        FileUploadDao dao = db.fileUploadDao();

        int uploaded = 0, failed = 0;

        for (File file : queueFiles) {
            if (!file.isFile()) continue;

            FileUploadRecord record = dao.getRecordByFileName(file.getName());
            if (record == null) {
                int nextSrNo = dao.getHighestSrNo() + 1;
                record = new FileUploadRecord(file.getName(), nextSrNo,
                        determineCategory(file.getName()), "pending", null, System.currentTimeMillis());
                dao.insert(record);
                Log.d(TAG, "Created upload record: " + file.getName() + " (srNo: " + nextSrNo + ")");
            }

            if ("success".equals(record.status)) {
                file.delete();
                continue;
            }

            Log.i(TAG, "Uploading: " + file.getName());
            uploadFile(context, file, record, dao);

            if ("success".equals(record.status)) {
                uploaded++;
                file.delete();
                Log.i(TAG, "Upload success: " + file.getName());
            } else {
                failed++;
                Log.w(TAG, "Upload failed: " + file.getName());
            }
        }

        Log.i(TAG, String.format("Upload summary - Success: %d, Failed: %d", uploaded, failed));
    }

    private static void retryFailedUploads(Context context) {
        try {
            AppDatabase db = AppDatabase.getInstance(context);
            FileUploadDao dao = db.fileUploadDao();

            long currentTime = System.currentTimeMillis();
            long thirtyMinutesAgo = currentTime - (30 * 60 * 1000);
            var retryUploads = dao.getFilesReadyForRetry(thirtyMinutesAgo);

            if (retryUploads.isEmpty()) {
                Log.d(TAG, "No uploads ready for retry (30+ minutes old)");
                return;
            }

            Log.i(TAG, "RETRY: Processing " + retryUploads.size() + " failed uploads");

            File queueDir = new File(context.getFilesDir(), QUEUE_DIR);

            for (FileUploadRecord record : retryUploads) {
                File file = new File(queueDir, record.fileName);
                if (!file.exists()) {
                    Log.w(TAG, "File missing, skipping: " + record.fileName);
                    continue;
                }

                Log.i(TAG, "RETRY: " + record.fileName + " - Original failure: " + record.failureReason);
                uploadFile(context, file, record, dao);

                if ("success".equals(record.status)) {
                    Log.i(TAG, "RETRY SUCCESS: " + record.fileName);
                    file.delete();
                } else {
                    Log.w(TAG, "RETRY FAILED: " + record.fileName + " - will retry in 30 min");
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error processing retry uploads", e);
        }
    }

    // ==============================================================================
    // UPLOAD SINGLE FILE — NOW WITH IMEI + DATE/TIME FROM FILENAME
    // Example: vehicle_mqtt_service_867672070005143_..._20251211_132616.log
    // → S3 path: mqtt_logs/867672070005143/2025-12-11/13-26-16/filename.log
    // ==============================================================================
    private static void uploadFile(Context context, File file, FileUploadRecord record, FileUploadDao dao) {
       // if (!file.exists() || file.length() == 0) {
        if(!file.exists()){
            Log.w(TAG, "File is empty or missing: " + file.getName());
            updateRecordFailure(record, dao, "File empty or missing");
            return;
        }

        long fileSize = file.length();
        Log.d(TAG, String.format("File: %s, size: %.2f MB", file.getName(), fileSize / (1024.0 * 1024.0)));

        try {
            // ← NEW: Build S3 path using IMEI + date/time from filename
            String s3Key = buildS3PathFromFilename(file.getName());
            String presignedUrl = getPresignedUrl(s3Key);
            boolean success = uploadToS3(file, presignedUrl, fileSize);

            if (success) {
                record.status = "success";
                record.failureReason = null;
                record.timestamp = System.currentTimeMillis();
                dao.update(record);
                Log.i(TAG, "Upload SUCCESS: " + file.getName());
            } else {
                updateRecordFailure(record, dao, "S3 upload failed");
            }

        } catch (Exception e) {
            Log.w(TAG, "Upload failed for " + file.getName() + ": " + e.getMessage());
            updateRecordFailure(record, dao, e.getMessage());
        }
    }

    private static String buildS3PathFromFilename(String filename) {
        try {
            String imei = DeviceSession.getImei();
            if (imei == null || imei.isEmpty()) {
                imei = "unknown";
            }

            String noExt = filename.replaceAll("(?i)\\.(bin|log|txt|gz)$", "");

            // Upload date (today)
            SimpleDateFormat uploadFmt =
                    new SimpleDateFormat("dd-MM-yyyy", Locale.US);
            String uploadDate = uploadFmt.format(new Date());

            String[] parts = filename.split("_");
            int n = parts.length;

            // File date & time from filename
            // File date & time from filename
            String year   = parts[n - 7];
            String month  = parts[n - 6];
            String day    = parts[n - 5];
            String hour   = parts[n - 4];
            String minute = parts[n - 3];


            return String.format(
                    "vehicles/vcu_logs_%s/%s/%s_%s_%s/%s_%s/%s",
                    imei,
                    uploadDate,
                    year, month, day,
                    hour, minute,
                    filename
            );

        } catch (Exception e) {
            Log.w(TAG, "Failed to parse filename: " + filename, e);
            return "vehicles/vcu_logs_" + DeviceSession.getImei()
                    + "/unknown/" + filename;
        }
    }
    private static String replaceImeiIfPresent(String fileName) {

        String imei = DeviceSession.getImei();

        if (imei == null || imei.isEmpty()) {

            Log.w("UploadManager", "IMEI not available, skipping rename");

            return fileName;

        }

        if (!fileName.contains("000000000000000")) {

            return fileName; // nothing to replace

        }

        return fileName.replace(

                "000000000000000",

                imei

        );

    }


    // ← FIXED: Safe JSON parsing (no more compile error)
    private static String getPresignedUrl(String fileName) throws IOException {
        String s3Key = fileName;  // Now we pass full path already

        String jsonBody = "{\n" +
                "  \"operation\": \"UL_LOGS\",\n" +
                "  \"fileName\": \"" + s3Key + "\",\n" +
                "  \"fileType\": \"application/octet-stream\"\n" +
                "}";

        Log.d(TAG, "Request JSON: " + jsonBody);

        RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));
        Request lambdaRequest = new Request.Builder()
                .url(LAMBDA_URL)
                .post(body)
                .build();

        Log.d(TAG, "Requesting pre-signed URL from: " + LAMBDA_URL);

        try (Response lambdaResponse = currentHttpClient.newCall(lambdaRequest).execute()) {
            if (!lambdaResponse.isSuccessful() || lambdaResponse.body() == null) {
                String errorMsg = "Failed to get pre-signed URL: " + lambdaResponse.code() + " " + lambdaResponse.message();
                Log.e(TAG, "Lambda Error: " + errorMsg);
                throw new IOException(errorMsg);
            }

            String responseBody = lambdaResponse.body().string();
            Log.d(TAG, "Lambda Response: " + responseBody);

            String presignedUrl = responseBody.trim();

            if (presignedUrl.startsWith("http")) {
                Log.d(TAG, "Received raw pre-signed URL from Lambda");
                return presignedUrl;
            }

            try {
                JSONObject jsonResponse = new JSONObject(presignedUrl);
                String url = jsonResponse.getString("url");
                Log.d(TAG, "Extracted pre-signed URL: [" + url.substring(0, Math.min(100, url.length())) + "]...");
                return url;
            } catch (JSONException e) {
                Log.e(TAG, "Failed to parse Lambda response: " + presignedUrl, e);
                throw new IOException("Invalid response from Lambda", e);
            }

        }
    }

    private static boolean uploadToS3(File file, String presignedUrl, long fileSize) throws IOException {
        Log.d(TAG, String.format("Starting S3 upload: %s (%.2f MB)", file.getName(), fileSize / (1024.0 * 1024.0)));

        OkHttpClient s3Client = createS3Client(fileSize);
        RequestBody fileBody = RequestBody.create(file, MediaType.parse("application/octet-stream"));

        Request s3Request = new Request.Builder()
                .url(presignedUrl)
                .put(fileBody)
                .build();

        long startTime = System.currentTimeMillis();
        try (Response s3Response = s3Client.newCall(s3Request).execute()) {
            long uploadTime = System.currentTimeMillis() - startTime;

            if (s3Response.isSuccessful()) {
                Log.i(TAG, String.format("S3 upload completed in %.1f seconds (HTTP %d)",
                        uploadTime / 1000.0, s3Response.code()));
                return true;
            } else {
                String errorBody = s3Response.body() != null ? s3Response.body().string() : "No response body";
                Log.e(TAG, String.format("S3 upload failed: HTTP %d %s | Error: %s",
                        s3Response.code(), s3Response.message(), errorBody));
                return false;
            }
        }
    }

    private static void updateRecordFailure(FileUploadRecord record, FileUploadDao dao, String reason) {
        record.status = "failed";
        record.failureReason = reason;
        record.timestamp = System.currentTimeMillis();
        dao.update(record);
        Log.d(TAG, "Failure recorded: " + reason);
        Log.i(TAG, "Will retry in 30 minutes");
    }

    private static String determineCategory(String fileName) {
        if (fileName.contains("boot_session") || fileName.contains("startup")) return "boot_logs";
        if (fileName.contains("error") || fileName.contains("crash")) return "error_logs";
        if (fileName.contains("sensor") || fileName.contains("telemetry")) return "sensor_data";
        return "general";
    }

    private static void copyFile(File source, File dest) throws IOException {
        try (FileInputStream fis = new FileInputStream(source);
             FileOutputStream fos = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) fos.write(buffer, 0, bytesRead);
            fos.flush();
        }
    }

    private static OkHttpClient createLambdaClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(120, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    private static OkHttpClient createS3Client(long fileSizeBytes) {
        int writeTimeoutSec = fileSizeBytes > 5 * 1024 * 1024 ? 600 : 300;
        int readTimeoutSec = fileSizeBytes > 5 * 1024 * 1024 ? 600 : 300;
        int callTimeoutSec = fileSizeBytes > 5 * 1024 * 1024 ? 1200 : 600;

        return new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(writeTimeoutSec, TimeUnit.SECONDS)
                .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
                .callTimeout(callTimeoutSec, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }
}




//
//package com.example.database.service;
//
//import android.content.Context;
//import android.util.Log;
//import com.example.database.db.AppDatabase;
//import com.example.database.db.FileUploadDao;
//import com.example.database.db.FileUploadRecord;
//import com.example.database.service.network.NetworkSelector;
//import com.example.database.utils.NetworkUtils;
//import org.json.JSONException;
//import org.json.JSONObject;
//import java.io.File;
//import java.io.FileInputStream;
//import java.io.FileOutputStream;
//import java.io.IOException;
//import java.util.concurrent.Executors;
//import java.util.concurrent.TimeUnit;
//import okhttp3.MediaType;
//import okhttp3.OkHttpClient;
//import okhttp3.Request;
//import okhttp3.RequestBody;
//import okhttp3.Response;
//
//public class UploadManager {
//
//    private static final String TAG = "UploadManager";
//    private static final String LOGS_SOURCE_DIR = "/data/system/udp_logs";
//    private static final String QUEUE_DIR = "upload_queue";
//
//    //private static final String LAMBDA_URL = "https://bpfsuu5xvj.execute-api.ap-south-1.amazonaws.com/default/s3uploadurlcreatorv1-dev-getPreSignedURLToPutToS3-dev";
//
//    private static final String LAMBDA_URL ="https://bpfsuu5xvj.execute-api.ap-south-1.amazonaws.com/default/s3uploadurlcreatorv1-dev-getPreSignedURLToPutToS3";
//
//    private static OkHttpClient currentHttpClient = createLambdaClient();
//
//
//
//
//    // ********************************************************************************
//
//
//    // ========================================================================
//    // NEW: EMERGENCY UPLOAD — BYPASSES WIFI CHECK — WORKS ON LTE/5G/WiFi
//    // ========================================================================
//    public static void processFilesForcedEmergency(Context context) {
//        Log.w(TAG, "EMERGENCY UPLOAD TRIGGERED — BYPASSING WIFI CHECK — USING ANY NETWORK");
//
//        NetworkSelector.Result bestNetwork = NetworkSelector.getBestNetwork(context);
//
//        if (bestNetwork == null || bestNetwork.network == null) {
//            Log.e(TAG, "No network available — cannot perform emergency upload");
//            return;
//        }
//
//        Log.w(TAG, "EMERGENCY USING BEST NETWORK → " + bestNetwork);
//
//        OkHttpClient emergencyClient = currentHttpClient.newBuilder()
//                .socketFactory(bestNetwork.network.getSocketFactory())
//                .build();
//
//        OkHttpClient previous = currentHttpClient;
//        currentHttpClient = emergencyClient;
//
//        try {
//            copyReadableFiles(context);
//            uploadFromQueue(context);
//            retryFailedUploads(context);
//
//            Log.w(TAG, "EMERGENCY UPLOAD COMPLETED SUCCESSFULLY — ALL LOGS SENT");
//        } catch (Exception e) {
//            Log.e(TAG, "Emergency upload failed completely", e);
//        } finally {
//            currentHttpClient = previous;
//        }
//    }
//
//    public static void processFiles(Context context) {
//        Executors.newSingleThreadExecutor().execute(() -> {
//            Log.i(TAG, "UploadManager: Starting upload process...");
//
//            if (!NetworkUtils.isWifiConnected(context)) {
//                Log.i(TAG, "Wi-Fi not connected, upload aborted");
//                return;
//            }
//
//            try {
//                copyReadableFiles(context);
//                uploadFromQueue(context);
//                retryFailedUploads(context);
//                Log.i(TAG, "Upload process completed");
//
//            } catch (Exception e) {
//                Log.e(TAG, "Error in upload process", e);
//            }
//        });
//    }
//
//
//    private static void copyReadableFiles(Context context) {
//        File logsSourceDir = new File(LOGS_SOURCE_DIR);
//        File queueDir = new File(context.getFilesDir(), QUEUE_DIR);
//
//        if (!queueDir.exists()) queueDir.mkdirs();
//
//        try {
//            File[] sourceFiles = logsSourceDir.listFiles();
//            if (sourceFiles == null) {
//                Log.w(TAG, "Cannot access " + LOGS_SOURCE_DIR + " (check folder exists + permissions)");
//                return;
//            }
//
//            AppDatabase db = AppDatabase.getInstance(context);
//            FileUploadDao dao = db.fileUploadDao();
//
//            int copied = 0, skipped = 0, errors = 0;
//
//            for (File sourceFile : sourceFiles) {
//                if (!sourceFile.isFile() || !sourceFile.canRead()) continue;
//
//                File queueFile = new File(queueDir, sourceFile.getName());
//                if (queueFile.exists()) { skipped++; continue; }
//
//                FileUploadRecord existing = dao.getRecordByFileName(sourceFile.getName());
//                if (existing != null) { skipped++; continue; }
//
//                try {
//                    copyFile(sourceFile, queueFile);
//                    copied++;
//                    Log.d(TAG, "Copied to queue: " + sourceFile.getName());
//                } catch (IOException e) {
//                    errors++;
//                    Log.e(TAG, "Copy failed: " + sourceFile.getName(), e);
//                }
//            }
//
//            Log.i(TAG, String.format("File copy summary - Copied: %d, Skipped: %d, Errors: %d", copied, skipped, errors));
//
//        } catch (Exception e) {
//            Log.e(TAG, "Error copying files from " + LOGS_SOURCE_DIR, e);
//        }
//    }
//
//    private static void uploadFromQueue(Context context) {
//        File queueDir = new File(context.getFilesDir(), QUEUE_DIR);
//        File[] queueFiles = queueDir.listFiles();
//
//        if (queueFiles == null || queueFiles.length == 0) {
//            Log.d(TAG, "Upload queue is empty");
//            return;
//        }
//
//        AppDatabase db = AppDatabase.getInstance(context);
//        FileUploadDao dao = db.fileUploadDao();
//
//        int uploaded = 0, failed = 0;
//
//        for (File file : queueFiles) {
//            if (!file.isFile()) continue;
//
//            FileUploadRecord record = dao.getRecordByFileName(file.getName());
//            if (record == null) {
//                int nextSrNo = dao.getHighestSrNo() + 1;
//                record = new FileUploadRecord(file.getName(), nextSrNo,
//                        determineCategory(file.getName()), "pending", null, System.currentTimeMillis());
//                dao.insert(record);
//                Log.d(TAG, "Created upload record: " + file.getName() + " (srNo: " + nextSrNo + ")");
//            }
//
//            if ("success".equals(record.status)) {
//                file.delete();
//                continue;
//            }
//
//            Log.i(TAG, "Uploading: " + file.getName());
//            uploadFile(context, file, record, dao);
//
//            if ("success".equals(record.status)) {
//                uploaded++;
//                file.delete();
//                Log.i(TAG, "Upload success: " + file.getName());
//            } else {
//                failed++;
//                Log.w(TAG, "Upload failed: " + file.getName());
//            }
//        }
//
//        Log.i(TAG, String.format("Upload summary - Success: %d, Failed: %d", uploaded, failed));
//    }
//
//    private static void retryFailedUploads(Context context) {
//        try {
//            AppDatabase db = AppDatabase.getInstance(context);
//            FileUploadDao dao = db.fileUploadDao();
//
//            long currentTime = System.currentTimeMillis();
//            long thirtyMinutesAgo = currentTime - (30 * 60 * 1000);
//            var retryUploads = dao.getFilesReadyForRetry(thirtyMinutesAgo);
//
//            if (retryUploads.isEmpty()) {
//                Log.d(TAG, "No uploads ready for retry (30+ minutes old)");
//                return;
//            }
//
//            Log.i(TAG, "RETRY: Processing " + retryUploads.size() + " failed uploads");
//
//            File queueDir = new File(context.getFilesDir(), QUEUE_DIR);
//
//            for (FileUploadRecord record : retryUploads) {
//                File file = new File(queueDir, record.fileName);
//                if (!file.exists()) {
//                    Log.w(TAG, "File missing, skipping: " + record.fileName);
//                    continue;
//                }
//
//                Log.i(TAG, "RETRY: " + record.fileName + " - Original failure: " + record.failureReason);
//                uploadFile(context, file, record, dao);
//
//                if ("success".equals(record.status)) {
//                    Log.i(TAG, "RETRY SUCCESS: " + record.fileName);
//                    file.delete();
//                } else {
//                    Log.w(TAG, "RETRY FAILED: " + record.fileName + " - will retry in 30 min");
//                }
//            }
//
//        } catch (Exception e) {
//            Log.e(TAG, "Error processing retry uploads", e);
//        }
//    }
//
//    // ==============================================================================
//    // UPLOAD SINGLE FILE — NOW WITH IMEI + DATE/TIME FROM FILENAME
//    // Example: vehicle_mqtt_service_867672070005143_..._20251211_132616.log
//    // → S3 path: mqtt_logs/867672070005143/2025-12-11/13-26-16/filename.log
//    // ==============================================================================
//    private static void uploadFile(Context context, File file, FileUploadRecord record, FileUploadDao dao) {
//        if (!file.exists() || file.length() == 0) {
//            Log.w(TAG, "File is empty or missing: " + file.getName());
//            updateRecordFailure(record, dao, "File empty or missing");
//            return;
//        }
//
//        long fileSize = file.length();
//        Log.d(TAG, String.format("File: %s, size: %.2f MB", file.getName(), fileSize / (1024.0 * 1024.0)));
//
//        try {
//            // ← NEW: Build S3 path using IMEI + date/time from filename
//            String s3Key = buildS3PathFromFilename(file.getName());
//            String presignedUrl = getPresignedUrl(s3Key);
//            boolean success = uploadToS3(file, presignedUrl, fileSize);
//
//            if (success) {
//                record.status = "success";
//                record.failureReason = null;
//                record.timestamp = System.currentTimeMillis();
//                dao.update(record);
//                Log.i(TAG, "Upload SUCCESS: " + file.getName());
//            } else {
//                updateRecordFailure(record, dao, "S3 upload failed");
//            }
//
//        } catch (Exception e) {
//            Log.w(TAG, "Upload failed for " + file.getName() + ": " + e.getMessage());
//            updateRecordFailure(record, dao, e.getMessage());
//        }
//    }
//
//    // ← NEW: Extract IMEI + date/time from filename and build correct S3 path
//    private static String buildS3PathFromFilename(String filename) {
//        try {
//            String[] parts = filename.split("_");
//            if (parts.length >= 6) {
//                String imei = parts[3];                    // 867672070005143
//                String dateStr = parts[5];                 // 20251211
//                String timeStr = parts[6].replace(".log", ""); // 132616
//
//                String year = dateStr.substring(0, 4);
//                String month = dateStr.substring(4, 6);
//                String day = dateStr.substring(6, 8);
//                String hour = timeStr.substring(0, 2);
//                String minute = timeStr.substring(2, 4);
//                String second = timeStr.substring(4, 6);
//
//                return String.format("mqtt_logs/%s/%s-%s-%s/%s-%s-%s/%s",
//                        imei, year, month, day, hour, minute, second, filename);
//            }
//        } catch (Exception e) {
//            Log.w(TAG, "Failed to parse filename for S3 path: " + filename, e);
//        }
//        return "mqtt_logs/unknown/" + filename;
//    }
//
//    // ← FIXED: Safe JSON parsing (no more compile error)
//    private static String getPresignedUrl(String fileName) throws IOException {
//        String s3Key = fileName;  // Now we pass full path already
//
//        String jsonBody = "{\n" +
//                "  \"operation\": \"UL_LOGS\",\n" +
//                "  \"fileName\": \"" + s3Key + "\",\n" +
//                "  \"fileType\": \"application/octet-stream\"\n" +
//                "}";
//
//        Log.d(TAG, "Request JSON: " + jsonBody);
//
//        RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));
//        Request lambdaRequest = new Request.Builder()
//                .url(LAMBDA_URL)
//                .post(body)
//                .build();
//
//        Log.d(TAG, "Requesting pre-signed URL from: " + LAMBDA_URL);
//
//        try (Response lambdaResponse = currentHttpClient.newCall(lambdaRequest).execute()) {
//            if (!lambdaResponse.isSuccessful() || lambdaResponse.body() == null) {
//                String errorMsg = "Failed to get pre-signed URL: " + lambdaResponse.code() + " " + lambdaResponse.message();
//                Log.e(TAG, "Lambda Error: " + errorMsg);
//                throw new IOException(errorMsg);
//            }
//
//            String responseBody = lambdaResponse.body().string();
//            Log.d(TAG, "Lambda Response: " + responseBody);
//
//            try {
//                JSONObject jsonResponse = new JSONObject(responseBody);
//                String presignedUrl = jsonResponse.getString("url");
//                Log.d(TAG, "Extracted pre-signed URL: [" + presignedUrl.substring(0, Math.min(100, presignedUrl.length())) + "]...");
//                return presignedUrl;
//            } catch (JSONException e) {
//                Log.e(TAG, "Failed to parse JSON response: " + responseBody, e);
//                throw new IOException("Invalid JSON from Lambda", e);
//            }
//        }
//    }
//
//    private static boolean uploadToS3(File file, String presignedUrl, long fileSize) throws IOException {
//        Log.d(TAG, String.format("Starting S3 upload: %s (%.2f MB)", file.getName(), fileSize / (1024.0 * 1024.0)));
//
//        OkHttpClient s3Client = createS3Client(fileSize);
//        RequestBody fileBody = RequestBody.create(file, MediaType.parse("application/octet-stream"));
//
//        Request s3Request = new Request.Builder()
//                .url(presignedUrl)
//                .put(fileBody)
//                .build();
//
//        long startTime = System.currentTimeMillis();
//        try (Response s3Response = s3Client.newCall(s3Request).execute()) {
//            long uploadTime = System.currentTimeMillis() - startTime;
//
//            if (s3Response.isSuccessful()) {
//                Log.i(TAG, String.format("S3 upload completed in %.1f seconds (HTTP %d)",
//                        uploadTime / 1000.0, s3Response.code()));
//                return true;
//            } else {
//                String errorBody = s3Response.body() != null ? s3Response.body().string() : "No response body";
//                Log.e(TAG, String.format("S3 upload failed: HTTP %d %s | Error: %s",
//                        s3Response.code(), s3Response.message(), errorBody));
//                return false;
//            }
//        }
//    }
//
//    private static void updateRecordFailure(FileUploadRecord record, FileUploadDao dao, String reason) {
//        record.status = "failed";
//        record.failureReason = reason;
//        record.timestamp = System.currentTimeMillis();
//        dao.update(record);
//        Log.d(TAG, "Failure recorded: " + reason);
//        Log.i(TAG, "Will retry in 30 minutes");
//    }
//
//    private static String determineCategory(String fileName) {
//        if (fileName.contains("boot_session") || fileName.contains("startup")) return "boot_logs";
//        if (fileName.contains("error") || fileName.contains("crash")) return "error_logs";
//        if (fileName.contains("sensor") || fileName.contains("telemetry")) return "sensor_data";
//        return "general";
//    }
//
//    private static void copyFile(File source, File dest) throws IOException {
//        try (FileInputStream fis = new FileInputStream(source);
//             FileOutputStream fos = new FileOutputStream(dest)) {
//            byte[] buffer = new byte[8192];
//            int bytesRead;
//            while ((bytesRead = fis.read(buffer)) != -1) fos.write(buffer, 0, bytesRead);
//            fos.flush();
//        }
//    }
//
//    private static OkHttpClient createLambdaClient() {
//        return new OkHttpClient.Builder()
//                .connectTimeout(60, TimeUnit.SECONDS)
//                .writeTimeout(60, TimeUnit.SECONDS)
//                .readTimeout(60, TimeUnit.SECONDS)
//                .callTimeout(120, TimeUnit.SECONDS)
//                .retryOnConnectionFailure(true)
//                .build();
//    }
//
//    private static OkHttpClient createS3Client(long fileSizeBytes) {
//        int writeTimeoutSec = fileSizeBytes > 5 * 1024 * 1024 ? 600 : 300;
//        int readTimeoutSec = fileSizeBytes > 5 * 1024 * 1024 ? 600 : 300;
//        int callTimeoutSec = fileSizeBytes > 5 * 1024 * 1024 ? 1200 : 600;
//
//        return new OkHttpClient.Builder()
//                .connectTimeout(60, TimeUnit.SECONDS)
//                .writeTimeout(writeTimeoutSec, TimeUnit.SECONDS)
//                .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
//                .callTimeout(callTimeoutSec, TimeUnit.SECONDS)
//                .retryOnConnectionFailure(true)
//                .build();
//    }
//}



//
//
//// FULL FILE STARTS HERE////////Latestes working code
//
//package com.example.database.service;
//
//import android.content.Context;
//import android.util.Log;
//import com.example.database.db.AppDatabase;
//import com.example.database.db.FileUploadDao;
//import com.example.database.db.FileUploadRecord;
//import com.example.database.service.network.NetworkSelector;  // ← ONLY THIS LINE ADDED
//import com.example.database.utils.NetworkUtils;
//import com.ultraviolette.uvmqtt.IMqttFileHandler;
//
//import org.json.JSONObject;
//import java.io.File;
//import java.io.FileInputStream;
//import java.io.FileOutputStream;
//import java.io.IOException;
//import java.util.concurrent.Executors;
//import java.util.concurrent.TimeUnit;
//
//import javax.net.SocketFactory;
//
//import okhttp3.MediaType;
//import okhttp3.OkHttpClient;
//import okhttp3.Request;
//import okhttp3.RequestBody;
//import okhttp3.Response;
//
//public class UploadManager {
//
//    private static final String TAG = "UploadManager";
//    private static final String LOGS_SOURCE_DIR = "/data/system/udp_logs";
//    private static final String QUEUE_DIR = "upload_queue";
//
//    private static final String LAMBDA_URL = "https://bpfsuu5xvj.execute-api.ap-south-1.amazonaws.com/default/s3uploadurlcreatorv1-dev-getPreSignedURLToPutToS3-dev";
//
//    private static OkHttpClient currentHttpClient = createLambdaClient();
//
//
//
//
//    // ********************************************************************************
//
//
//    // ========================================================================
//    // NEW: EMERGENCY UPLOAD — BYPASSES WIFI CHECK — WORKS ON LTE/5G/WiFi
//    // ========================================================================
//    public static void processFilesForcedEmergency(Context context) {
//        Log.w(TAG, "EMERGENCY UPLOAD TRIGGERED — BYPASSING WIFI CHECK — USING ANY NETWORK");
//
//        NetworkSelector.Result bestNetwork = NetworkSelector.getBestNetwork(context);
//
//        if (bestNetwork == null || bestNetwork.network == null) {
//            Log.e(TAG, "No network available — cannot perform emergency upload");
//            return;
//        }
//
//        Log.w(TAG, "EMERGENCY USING BEST NETWORK → " + bestNetwork);
//
//        OkHttpClient emergencyClient = currentHttpClient.newBuilder()
//                .socketFactory(bestNetwork.network.getSocketFactory())
//                .build();
//
//        OkHttpClient previous = currentHttpClient;
//        currentHttpClient = emergencyClient;
//
//        try {
//            copyReadableFiles(context);
//            uploadFromQueue(context);
//            retryFailedUploads(context);
//
//            Log.w(TAG, "EMERGENCY UPLOAD COMPLETED SUCCESSFULLY — ALL LOGS SENT");
//        } catch (Exception e) {
//            Log.e(TAG, "Emergency upload failed completely", e);
//        } finally {
//            currentHttpClient = previous;
//        }
//    }
//
//    public static void processFiles(Context context) {
//        Executors.newSingleThreadExecutor().execute(() -> {
//            Log.i(TAG, "UploadManager: Starting upload process...");
//
//            if (!NetworkUtils.isWifiConnected(context)) {
//                Log.i(TAG, "Wi-Fi not connected, upload aborted");
//                return;
//            }
//
//            try {
//                copyReadableFiles(context);
//                uploadFromQueue(context);
//                retryFailedUploads(context);
//                Log.i(TAG, "Upload process completed");
//
//            } catch (Exception e) {
//                Log.e(TAG, "Error in upload process", e);
//            }
//        });
//    }
//
//
//    private static void copyReadableFiles(Context context) {
//        File logsSourceDir = new File(LOGS_SOURCE_DIR);
//        File queueDir = new File(context.getFilesDir(), QUEUE_DIR);
//
//        if (!queueDir.exists()) queueDir.mkdirs();
//
//        try {
//            File[] sourceFiles = logsSourceDir.listFiles();
//            if (sourceFiles == null) {
//                Log.w(TAG, "Cannot access " + LOGS_SOURCE_DIR + " (check folder exists + permissions)");
//                return;
//            }
//
//            AppDatabase db = AppDatabase.getInstance(context);
//            FileUploadDao dao = db.fileUploadDao();
//
//            int copied = 0, skipped = 0, errors = 0;
//
//            for (File sourceFile : sourceFiles) {
//                if (!sourceFile.isFile() || !sourceFile.canRead()) continue;
//
//                // ===== NEW SAFETY CHECK (ADDED, NO COMMENT CHANGED) =====
//                if (!isFileStable(sourceFile)) {
//                    Log.w(TAG, "Skipping unstable file (still being written): " + sourceFile.getName());
//                    continue;
//                }
//                // ======================================================
//
//                File queueFile = new File(queueDir, sourceFile.getName());
//                if (queueFile.exists()) { skipped++; continue; }
//
//                FileUploadRecord existing = dao.getRecordByFileName(sourceFile.getName());
//                if (existing != null) { skipped++; continue; }
//
//                try {
//                    copyFile(sourceFile, queueFile);
//                    copied++;
//                    Log.d(TAG, "Copied to queue: " + sourceFile.getName());
//                } catch (IOException e) {
//                    errors++;
//                    Log.e(TAG, "Copy failed: " + sourceFile.getName(), e);
//                }
//            }
//
//            Log.i(TAG, String.format("File copy summary - Copied: %d, Skipped: %d, Errors: %d", copied, skipped, errors));
//
//        } catch (Exception e) {
//            Log.e(TAG, "Error copying files from " + LOGS_SOURCE_DIR, e);
//        }
//    }
//
//    private static void uploadFromQueue(Context context) {
//        File queueDir = new File(context.getFilesDir(), QUEUE_DIR);
//        File[] queueFiles = queueDir.listFiles();
//
//        if (queueFiles == null || queueFiles.length == 0) {
//            Log.d(TAG, "Upload queue is empty");
//            return;
//        }
//
//        AppDatabase db = AppDatabase.getInstance(context);
//        FileUploadDao dao = db.fileUploadDao();
//
//        int uploaded = 0, failed = 0;
//
//        for (File file : queueFiles) {
//            if (!file.isFile()) continue;
//
//            FileUploadRecord record = dao.getRecordByFileName(file.getName());
//            if (record == null) {
//                int nextSrNo = dao.getHighestSrNo() + 1;
//                record = new FileUploadRecord(file.getName(), nextSrNo,
//                        determineCategory(file.getName()), "pending", null, System.currentTimeMillis());
//                dao.insert(record);
//                Log.d(TAG, "Created upload record: " + file.getName() + " (srNo: " + nextSrNo + ")");
//            }
//
//            if ("success".equals(record.status)) {
//                file.delete();
//                continue;
//            }
//
//            Log.i(TAG, "Uploading: " + file.getName());
//            uploadFile(context, file, record, dao);
//
//            if ("success".equals(record.status)) {
//                uploaded++;
//                file.delete();
//                Log.i(TAG, "Upload success: " + file.getName());
//            } else {
//                failed++;
//                Log.w(TAG, "Upload failed: " + file.getName());
//            }
//        }
//
//        Log.i(TAG, String.format("Upload summary - Success: %d, Failed: %d", uploaded, failed));
//    }
//
//    private static void retryFailedUploads(Context context) {
//        try {
//            AppDatabase db = AppDatabase.getInstance(context);
//            FileUploadDao dao = db.fileUploadDao();
//
//            long currentTime = System.currentTimeMillis();
//            long thirtyMinutesAgo = currentTime - (30 * 60 * 1000);
//            var retryUploads = dao.getFilesReadyForRetry(thirtyMinutesAgo);
//
//            if (retryUploads.isEmpty()) {
//                Log.d(TAG, "No uploads ready for retry (30+ minutes old)");
//                return;
//            }
//
//            Log.i(TAG, "RETRY: Processing " + retryUploads.size() + " failed uploads");
//
//            File queueDir = new File(context.getFilesDir(), QUEUE_DIR);
//
//            for (FileUploadRecord record : retryUploads) {
//                File file = new File(queueDir, record.fileName);
//                if (!file.exists()) {
//                    Log.w(TAG, "File missing, skipping: " + record.fileName);
//                    continue;
//                }
//
//                Log.i(TAG, "RETRY: " + record.fileName + " - Original failure: " + record.failureReason);
//                uploadFile(context, file, record, dao);
//
//                if ("success".equals(record.status)) {
//                    Log.i(TAG, "RETRY SUCCESS: " + record.fileName);
//                    file.delete();
//                } else {
//                    Log.w(TAG, "RETRY FAILED: " + record.fileName + " - will retry in 30 min");
//                }
//            }
//
//        } catch (Exception e) {
//            Log.e(TAG, "Error processing retry uploads", e);
//        }
//    }
//
//    private static void uploadFile(Context context, File file, FileUploadRecord record, FileUploadDao dao) {
//
//        // --------- FIXED SECTION START ---------
//
//        if (!file.exists()) {
//            Log.w(TAG, "File is missing: " + file.getName());
//            updateRecordFailure(record, dao, "File missing");
//            return;
//        }
//
//        boolean readable = false;
//        try (FileInputStream fis = new FileInputStream(file)) {
//            byte[] buffer = new byte[1];
//            int read = fis.read(buffer);
//            if (read > 0) {
//                readable = true;
//            }
//        } catch (Exception e) {
//            Log.w(TAG, "File read failed: " + file.getName() + " reason: " + e);
//            updateRecordFailure(record, dao, "File unreadable: " + e.getMessage());
//            return;
//        }
//
//        if (!readable) {
//            Log.w(TAG, "File exists but could not read even 1 byte: " + file.getName());
//            updateRecordFailure(record, dao, "File unreadable");
//            return;
//        }
//
//        // --------- FIXED SECTION END ---------
//
//        long fileSize = file.length();
//        Log.d(TAG, String.format("File: %s, size: %.2f MB", file.getName(), fileSize / (1024.0 * 1024.0)));
//
//        try {
//            String presignedUrl = getPresignedUrl(file.getName());
//
//            boolean success = uploadToS3(file, presignedUrl, fileSize);
//
//            if (success) {
//                record.status = "success";
//                record.failureReason = null;
//                record.timestamp = System.currentTimeMillis();
//                dao.update(record);
//                Log.i(TAG, "Upload SUCCESS: " + file.getName());
//            } else {
//                updateRecordFailure(record, dao, "S3 upload failed");
//            }
//
//        } catch (Exception e) {
//            Log.w(TAG, "Upload failed for " + file.getName() + ": " + e.getMessage());
//            updateRecordFailure(record, dao, e.getMessage());
//        }
//    }
//
//    // ===== NEW METHOD (ADDED, NO EXISTING CODE TOUCHED) =====
//    private static boolean isFileStable(File file) {
//        try {
//            long size1 = file.length();
//            Thread.sleep(2000);
//            long size2 = file.length();
//            return size1 > 0 && size1 == size2;
//        } catch (Exception e) {
//            return false;
//        }
//    }
//    // ======================================================
//
//    private static String getPresignedUrl(String fileName)
//            throws IOException, org.json.JSONException {
//
//        String s3Key = "test/" + fileName;
//
//        String jsonBody = "{\n" +
//                "  \"operation\": \"UL_LOGS\",\n" +
//                "  \"fileName\": \"" + s3Key + "\",\n" +
//                "  \"fileType\": \"application/octet-stream\"\n" +
//                "}";
//
//        Log.d(TAG, "Request JSON: " + jsonBody);
//
//        RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));
//        Request lambdaRequest = new Request.Builder()
//                .url(LAMBDA_URL)
//                .post(body)
//                .build();
//
//        try (Response lambdaResponse = currentHttpClient.newCall(lambdaRequest).execute()) {
//            if (!lambdaResponse.isSuccessful() || lambdaResponse.body() == null) {
//                throw new IOException("Failed to get pre-signed URL");
//            }
//
//            JSONObject jsonResponse = new JSONObject(lambdaResponse.body().string());
//            return jsonResponse.getString("url");
//        }
//    }
//
//    private static boolean uploadToS3(File file, String presignedUrl, long fileSize) throws IOException {
//        OkHttpClient s3Client = createS3Client(fileSize, currentHttpClient.socketFactory());
//
//        RequestBody fileBody = RequestBody.create(file, MediaType.parse("application/octet-stream"));
//
//        Request s3Request = new Request.Builder()
//                .url(presignedUrl)
//                .put(fileBody)
//                .build();
//
//        try (Response s3Response = s3Client.newCall(s3Request).execute()) {
//            return s3Response.isSuccessful();
//        }
//    }
//
//    private static void updateRecordFailure(FileUploadRecord record, FileUploadDao dao, String reason) {
//        record.status = "failed";
//        record.failureReason = reason;
//        record.timestamp = System.currentTimeMillis();
//        dao.update(record);
//        Log.d(TAG, "Failure recorded: " + reason);
//        Log.i(TAG, "Will retry in 30 minutes");
//    }
//
//    private static String determineCategory(String fileName) {
//        if (fileName.contains("boot_session") || fileName.contains("startup")) return "boot_logs";
//        if (fileName.contains("error") || fileName.contains("crash")) return "error_logs";
//        if (fileName.contains("sensor") || fileName.contains("telemetry")) return "sensor_data";
//        return "general";
//    }
//
//    private static void copyFile(File source, File dest) throws IOException {
//        try (FileInputStream fis = new FileInputStream(source);
//             FileOutputStream fos = new FileOutputStream(dest)) {
//            byte[] buffer = new byte[8192];
//            int bytesRead;
//            while ((bytesRead = fis.read(buffer)) != -1) fos.write(buffer, 0, bytesRead);
//            fos.flush();
//        }
//    }
//
//    private static OkHttpClient createLambdaClient() {
//        return new OkHttpClient.Builder()
//                .connectTimeout(60, TimeUnit.SECONDS)
//                .writeTimeout(60, TimeUnit.SECONDS)
//                .readTimeout(60, TimeUnit.SECONDS)
//                .callTimeout(120, TimeUnit.SECONDS)
//                .retryOnConnectionFailure(true)
//                .build();
//    }
//
//    private static OkHttpClient createS3Client(long fileSizeBytes, SocketFactory sf) {
//        int writeTimeoutSec = fileSizeBytes > 5 * 1024 * 1024 ? 600 : 300;
//        int readTimeoutSec = fileSizeBytes > 5 * 1024 * 1024 ? 600 : 300;
//        int callTimeoutSec = fileSizeBytes > 5 * 1024 * 1024 ? 1200 : 600;
//
//        return new OkHttpClient.Builder()
//                .connectTimeout(60, TimeUnit.SECONDS)
//                .writeTimeout(writeTimeoutSec, TimeUnit.SECONDS)
//                .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
//                .callTimeout(callTimeoutSec, TimeUnit.SECONDS)
//                .retryOnConnectionFailure(true)
//                .socketFactory(sf)
//                .build();
//    }
//}
//
//// END OF FILE
