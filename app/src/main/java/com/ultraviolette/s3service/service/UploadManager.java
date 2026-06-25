package com.ultraviolette.s3service.service;
import com.ultraviolette.s3service.utils.DeviceSession;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import android.content.Context;
import android.util.Log;
import com.ultraviolette.s3service.db.AppDatabase;
import com.ultraviolette.s3service.db.FileUploadDao;
import com.ultraviolette.s3service.db.FileUploadRecord;
import com.ultraviolette.s3service.service.network.NetworkSelector;

import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
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
    private static final String LOGS_SOURCE_DIR = "/data/vendor/udp_logs";

    private static final String THOMB_LOGS_DIR = "/data/vendor/logmaster/thomb_logs";

    private static final String DEBUG_LOGS_DIR = "/data/vendor/logmaster/debug_logs";
    private static final String ANR_LOGS_DIR = "/data/vendor/logmaster/anr_logs";

   // private static final String QUEUE_DIR = "upload_queue";

    //private static final String LAMBDA_URL = "https://bpfsuu5xvj.execute-api.ap-south-1.amazonaws.com/default/s3uploadurlcreatorv1-dev-getPreSignedURLToPutToS3-dev";

    private static final String LAMBDA_URL ="https://bpfsuu5xvj.execute-api.ap-south-1.amazonaws.com/default/s3uploadurlcreatorv1-dev-getPreSignedURLToPutToS3";


    private static final Object UPLOAD_LOCK = new Object();
    private static boolean isUploading = false;

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

//        OkHttpClient emergencyClient = currentHttpClient.newBuilder()
//                .socketFactory(bestNetwork.network.getSocketFactory())
//                .build();
        OkHttpClient emergencyClient = currentHttpClient;


        OkHttpClient previous = currentHttpClient;
        currentHttpClient = emergencyClient;

        try {
            uploadDirectlyFromSource(context);
            retryFailedUploads(context);

            Log.w(TAG, "EMERGENCY UPLOAD COMPLETED SUCCESSFULLY — ALL LOGS SENT");
        } catch (Exception e) {
            Log.e(TAG, "Emergency upload failed completely", e);
        } finally {
            currentHttpClient = previous;
        }
    }

// selects the whatever network is availaible if both are there then it will compare and pick the network

    public static void processFiles(Context context) {

        Executors.newSingleThreadExecutor().execute(() -> {

            synchronized (UPLOAD_LOCK) {
                if (isUploading) {
                    Log.w(TAG, "Upload already running → skipping trigger");
                    return;
                }
                isUploading = true;
            }

            try {
                Log.i(TAG, "UploadManager: Starting upload process (ANY NETWORK)");

                NetworkSelector.Result bestNetwork = NetworkSelector.getBestNetwork(context);

                if (bestNetwork == null || bestNetwork.network == null) {
                    Log.e(TAG, "No network available — upload aborted");
                    return;
                }



                OkHttpClient networkClient = currentHttpClient;


                OkHttpClient previous = currentHttpClient;
                currentHttpClient = networkClient;

                try {
                    uploadDirectlyFromSource(context);
                    retryFailedUploads(context);
                    processThombLogs(context);
                    processDebugLogs(context);
                    processAnrLogs(context);
                    Log.i(TAG, "Upload process completed on " + bestNetwork);
                } catch (Exception e) {
                    Log.e(TAG, "Error in upload process", e);
                } finally {
                    currentHttpClient = previous;
                }

            } finally {
                synchronized (UPLOAD_LOCK) {
                    isUploading = false;
                }
            }
        });
    }




    public static void processFilesNow(Context context) {

        Log.i(TAG, "=== MANUAL UPLOAD STARTED ===");

        NetworkSelector.Result bestNetwork =
                NetworkSelector.getBestNetwork(context);

        if (bestNetwork == null || bestNetwork.network == null) {

            Log.w(TAG,
                    "No preferred network found — continuing anyway");

        } else {

            Log.i(TAG, "Using network: " + bestNetwork);

        }

        try {

            uploadDebugLogsSequentially(context);

            Log.i(TAG, "Manual debug_logs upload finished");

        } catch (Exception e) {

            Log.e(TAG, "Manual upload failed", e);

        }
    }

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

    // ======================== NEW DIRECT UPLOAD ========================

//    private static void uploadDirectlyFromSource(Context context) {
//        File logsSourceDir = new File(LOGS_SOURCE_DIR);
//
//        List<File> sourceFiles = new ArrayList<>();
//        collectFilesRecursively(logsSourceDir, sourceFiles);
//        sourceFiles.sort((f1, f2) ->
//                Integer.compare(extractBootCounter(f2.getName()), extractBootCounter(f1.getName()))
//        );
//
//
//        if (sourceFiles.isEmpty())
//            Log.d(TAG, "No VCU logs found");
//
//        String activeSession = getActiveSessionId();
//
//        AppDatabase db = AppDatabase.getInstance(context);
//        FileUploadDao dao = db.fileUploadDao();
//
//
//
//        for (File file : sourceFiles) {
//
//            if ("boot_counter.txt".equals(file.getName())) continue;
//
//
//
//            String name = file.getName().toLowerCase();
//            if (!(name.endsWith(".bin") || name.endsWith(".gz") || name.endsWith(".txt"))) continue;
//
//
//
//            if (!file.isFile() || !file.canRead() || file.length() == 0) {
//                Log.d(TAG, "Skipping empty/unreadable file: " + file.getName());
//                continue;
//
//            }
//
//            String fileSession = extractSessionId(file.getName());
//
//            if (activeSession != null && activeSession.equals(fileSession)) {
//                continue;
//            }
//
//            int fileCounter = extractBootCounter(file.getName());
//
//
//
//            Log.d(TAG, "FILE: " + file.getName() +
//                    " | session=" + fileSession +
//                    " | active=" + activeSession);



                           /// VENDOR
private static void uploadDirectlyFromSource(Context context) {

    File logsSourceDir = new File(LOGS_SOURCE_DIR);

    // 🔍 Check directory existence
    Log.d(TAG, "VCU Log Source Directory Path: " + logsSourceDir.getAbsolutePath());

    if (!logsSourceDir.exists()) {
        Log.e(TAG, "Source directory DOES NOT exist ");
        return;
    }

    if (!logsSourceDir.isDirectory()) {
        Log.e(TAG, "Source path is NOT a directory ");
        return;
    }

    if (!logsSourceDir.canRead()) {
        Log.e(TAG, "Directory exists but NOT readable  Permission issue");
        return;
    }

    Log.d(TAG, "Directory accessible ");

    File[] filesCheck = logsSourceDir.listFiles();

    if (filesCheck == null) {
        Log.e(TAG, "listFiles() returned NULL → Possible permission issue ");
        return;
    }

    Log.d(TAG, "Total files found in source directory: " + filesCheck.length);

    List<File> sourceFiles = new ArrayList<>();

    collectFilesRecursively(logsSourceDir, sourceFiles);

    Log.d(TAG, "Total files after recursive scan: " + sourceFiles.size());

    sourceFiles.sort((f1, f2) ->
            Integer.compare(extractBootCounter(f2.getName()), extractBootCounter(f1.getName()))
    );

    if (sourceFiles.isEmpty()) {
        Log.d(TAG, "No VCU logs found in directory ");
        return;
    }

    String activeSession = getActiveSessionId();

    Log.d(TAG, "Active Session ID: " + activeSession);

    AppDatabase db = AppDatabase.getInstance(context);
    FileUploadDao dao = db.fileUploadDao();

    for (File file : sourceFiles) {

        Log.d(TAG, "Processing file: " + file.getAbsolutePath());

        if ("boot_counter.txt".equals(file.getName())) {
            Log.d(TAG, "Skipping boot_counter.txt");
            continue;
        }

        String name = file.getName().toLowerCase();

        if (!(name.endsWith(".bin") || name.endsWith(".gz") || name.endsWith(".txt"))) {
            Log.d(TAG, "Skipping unsupported file: " + file.getName());
            continue;
        }

        if (!file.exists()) {
            Log.e(TAG, "File does NOT exist: " + file.getName());
            continue;
        }

        if (!file.canRead()) {
            Log.e(TAG, "File NOT readable (permission issue): " + file.getName());
            continue;
        }

        if (file.length() == 0) {
            Log.d(TAG, "Skipping empty file: " + file.getName());
            continue;
        }

        String fileSession = extractSessionId(file.getName());

        if (activeSession != null && activeSession.equals(fileSession)) {
            Log.d(TAG, "Skipping ACTIVE session file: " + file.getName());
            continue;
        }

        int fileCounter = extractBootCounter(file.getName());

        Log.d(TAG,
                "VALID FILE FOUND  -> " +
                        "Name=" + file.getName() +
                        " | Session=" + fileSession +
                        " | BootCounter=" + fileCounter);


        // Continue upload logic here



            FileUploadRecord record = dao.getRecordByFileName(file.getName());

            if (record == null) {
                int next = dao.getHighestSrNo() + 1;
                record = new FileUploadRecord(
                        file.getName(),
                        next,
                        determineCategory(file.getName()),
                        "pending",
                        null,
                        System.currentTimeMillis()
                );
                dao.insert(record);
            }

            if ("success".equals(record.status)) {
                Log.d(TAG, "SKIPPING (already uploaded): " + file.getName());
                continue;
            }
            Log.d(TAG, "UPLOAD ORDERRRrEIuYnew → " + file.getName() +
                    " | counter=" + extractBootCounter(file.getName()));


            uploadFile(context, file, record, dao);



}
    }


    private static int extractBootCounter(String filename) {
        try {

            // Check if it's a dropbox file - return 0 explicitly
            if (filename.contains("@")) {
                return 0;
            }
            // Remove extension (.bin/.gz/.txt) in BOTH upper & lower case
            String name = filename.replaceAll("(?i)\\.(bin|gz|txt)$", "");

            String[] parts = name.split("_");

            if (parts.length == 0) return 0;

            // Last element = boot counter
            String counterStr = parts[parts.length - 1];

            return Integer.parseInt(counterStr);

        } catch (Exception e) {
            Log.w(TAG, "Failed to extract counter from: " + filename, e);
            return 0;
        }
    }
    private static void uploadSpecialDirectory(Context context,
                                               String dirPath,
                                               String categoryFolder) {

        File root = new File(dirPath);

        Log.i(TAG, "Checkingggg directoryyyy: " + dirPath);

        if (!root.exists()) {
            Log.e(TAG, "Crash directory does not exist: " + dirPath);
            return;
        }

        if (!root.canRead()) {
            Log.e(TAG, "Crash directory not readable: " + dirPath);
            return;
        }

        List<File> files = new ArrayList<>();
        collectFilesRecursively(root, files);

        Log.i(TAG, "Crash filesyyy found: " + files.size());

        if (files.isEmpty()) return;

        AppDatabase db = AppDatabase.getInstance(context);
        FileUploadDao dao = db.fileUploadDao();

        for (File file : files) {

            if (!file.isFile() || !file.canRead()) {
                Log.w(TAG, "Skipping unreadable crash file: " + file.getAbsolutePath());
                continue;
            }

            // rename crash files to avoid duplicate names
            long age = System.currentTimeMillis() - file.lastModified();
            if (age < 10000) {
                Log.i(TAG, "Skipping crash file still being written: " + file.getName());
                continue;
            }

// rename crash files to avoid duplicate names
            String name = file.getName();

            if ((name.startsWith("tombstone") || name.startsWith("anr")) && !name.matches("^\\d+_.*")) {

                String newName = System.currentTimeMillis() + "_" + file.getName();

                File renamed = new File(file.getParent(), newName);

                if (file.renameTo(renamed)) {
                    file = renamed;
                }
            }


            FileUploadRecord record = dao.getRecordByFileName(file.getName());

            if (record == null) {
                int next = dao.getHighestSrNo() + 1;

                record = new FileUploadRecord(
                        file.getName(),
                        next,
                        categoryFolder,
                        "pending",
                        null,
                        System.currentTimeMillis()
                );

                dao.insert(record);
            }

            if ("success".equals(record.status)) continue;


            uploadSpecialFile(context, file, record, dao, categoryFolder);
        }
    }


    private static void uploadSpecialFile(Context context,
                                          File file,
                                          FileUploadRecord record,
                                          FileUploadDao dao,
                                          String categoryFolder) {

        try {

            String imei = DeviceSession.getImei();
            if (imei == null || imei.isEmpty()) imei = "unknown";

            SimpleDateFormat outerFmt = new SimpleDateFormat("dd-MM-yyyy", Locale.US);
            String outerDate = outerFmt.format(new Date());

            SimpleDateFormat innerFmt = new SimpleDateFormat("yyyy_MM_dd", Locale.US);
            String innerDate = innerFmt.format(new Date());

            String s3Key = String.format(
                    "android_hv/vcu_logs_%s/%s/%s/dropbox/%s",
                    imei,
                    outerDate,
                    innerDate,
                    file.getName()
            );
            String presignedUrl = getPresignedUrl(s3Key);

            boolean success = uploadToS3(file, presignedUrl, file.length());

            if (success) {
                record.status = "success";
                record.failureReason = null;

                // DELETE DROPBOX FILE FROM UDP_LOGS AFTER SUCCESSFUL UPLOAD
                if ("dropbox".equals(categoryFolder)) {
                    if (file.delete()) {
                        Log.i(TAG, "Deleted DropBox file from udp_logs: " + file.getName());
                    } else {
                        Log.w(TAG, " Failed to delete from udp_logs: " + file.getName());
                    }

                }
            } else {
                record.status = "failed";
                record.failureReason = "S3 upload failed";
            }

            record.timestamp = System.currentTimeMillis();
            dao.update(record);

        } catch (Exception e) {
            updateRecordFailure(record, dao, e.getMessage());
        }
    }
    private static String getActiveSessionId() {
        try {
            Process p = Runtime.getRuntime().exec(
//                   new String[]{"getprop", "persist.sys.uv.boot_session"}
                    new String[]{"getprop", "persist.vendor.uv.boot_session"}

            );

            java.io.BufferedReader reader =
                    new java.io.BufferedReader(
                            new java.io.InputStreamReader(p.getInputStream())
                    );

            String session = reader.readLine();

            if (session != null) {
                session = session.trim();
                Log.i(TAG, "Active boot session from getprop: " + session);
                return session;
            }

        } catch (Exception e) {
            Log.w(TAG, "Failed to read persist.sys.uv.boot_session", e);
        }

        return null;
    }
    private static String extractSessionId(String filename) {
        try {
            String[] parts = filename.split("_");

            if (filename.endsWith(".txt")) {
                // sg_app_IMEI_SESSION_...
                return parts.length > 3 ? parts[3] : null;
            } else {
                // bms_IMEI_SESSION_... or mot_IMEI_SESSION_...
                return parts.length > 2 ? parts[2] : null;
            }

        } catch (Exception e) {
            return null;
        }
    }



    private static File findFileRecursively(File dir, String filename) {
        File[] files = dir.listFiles();
        if (files == null) return null;

        for (File f : files) {
            if (f.isDirectory()) {
                File result = findFileRecursively(f, filename);
                if (result != null) return result;
            } else if (f.getName().equals(filename)) {
                return f;
            }
        }
        return null;
    }

    private static void retryFailedUploads(Context context) {
        try {
            AppDatabase db = AppDatabase.getInstance(context);
            FileUploadDao dao = db.fileUploadDao();

            long currentTime = System.currentTimeMillis();
            long thirtyMinutesAgo = currentTime - (30 * 60 * 1000);
            var retryUploads = dao.getFilesReadyForRetry(thirtyMinutesAgo);

            if (retryUploads.isEmpty()) return;

            for (FileUploadRecord record : retryUploads) {

                File file = findFileRecursively(new File(LOGS_SOURCE_DIR), record.fileName);

//                if (file == null) {
//                    file = findFileRecursively(new File(CRASH_LOG_DIR), record.fileName);
//                }

                if (file == null || !file.exists()) continue;

                if ("crash".equals(record.category)) {
                    uploadSpecialFile(context, file, record, dao, record.category);
                } else {
                    uploadFile(context, file, record, dao);
                }
            }

//                if ("success".equals(record.status)) {
//                    file.delete();
//                }


        } catch (Exception e) {
            Log.e(TAG, "Error processing retry uploads", e);
        }
    }



    private static void uploadFile(Context context, File file, FileUploadRecord record, FileUploadDao dao) {

        // FIX: check both existence AND size
        if (!file.exists() || file.length() == 0) {
            Log.w(TAG, "Skipping empty file permanently: " + file.getName());

            record.status = "success"; // or "ignored"
            record.failureReason = "empty file skipped";
            record.timestamp = System.currentTimeMillis();
            dao.update(record);

            return;
        }

        long fileSize = file.length();
        Log.d(TAG, String.format("File: %s, size: %.2f MB", file.getName(), fileSize / (1024.0 * 1024.0)));

        try {
            String s3Key = buildS3PathFromFilename(file.getName());
            String presignedUrl = getPresignedUrl(s3Key);
            boolean success = uploadToS3(file, presignedUrl, fileSize);

            if (success) {
                record.status = "success";
                record.failureReason = null;
                record.timestamp = System.currentTimeMillis();
                dao.update(record);
                Log.i(TAG, "Upload SUCCESSsssssEEEnew: " + file.getName());
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
            if (imei == null || imei.isEmpty()) imei = "unknown";

            SimpleDateFormat uploadFmt = new SimpleDateFormat("dd-MM-yyyy", Locale.US);
            String uploadDate = uploadFmt.format(new Date());

            // ✅ CHECK IF IT'S A DROPBOX FILE
            String[] dropboxTags = {
                    "SYSTEM_BOOT@", "SYSTEM_TOMBSTONE@", "storage_trim@",
                    "system_app_crash@", "system_app_strictmode@", "system_server_strictmode@"
            };

//            for (String tag : dropboxTags) {
//                if (filename.contains(tag)) {
//                    // ✅ Simple path for dropbox files
//                    return String.format(
//                            "android_hv/vcu_logs_%s/%s/dropbox/%s",
//                            imei,
//                            uploadDate,
//                            filename
//                    );
//                }
//            }


            for (String tag : dropboxTags) {
                if (filename.contains(tag)) {

                    SimpleDateFormat innerFmt =
                            new SimpleDateFormat("yyyy_MM_dd", Locale.US);

                    String innerDate = innerFmt.format(new Date());

                    return String.format(
                            "android_hv/vcu_logs_%s/%s/%s/dropbox/%s",
                            imei,
                            uploadDate,
                            innerDate,
                            filename
                    );
                }
            }

            String[] parts = filename.split("_");
            int n = parts.length;

            String year, month, day, hour, minute;
            String lowerFilename = filename.toLowerCase();

            if (lowerFilename.endsWith(".gz") || lowerFilename.endsWith(".bin")) {

                year   = parts[n - 8];
                month  = parts[n - 7];
                day    = parts[n - 6];
                hour   = parts[n - 5];
                minute = parts[n - 4];
            } else {
                year   = parts[n - 7];
                month  = parts[n - 6];
                day    = parts[n - 5];
                hour   = parts[n - 4];
                minute = parts[n - 3];
            }

            return String.format(
                    "android_hv/vcu_logs_%s/%s/%s_%s_%s/%s_%s/%s",
                    imei,
                    uploadDate,
                    year, month, day,
                    hour, minute,
                    filename
            );

        } catch (Exception e) {
            Log.w(TAG, "Failed to parse filename: " + filename, e);
            return "android_hv/vcu_logs_" + DeviceSession.getImei()
                    + "/unknown/" + filename;
        }
    }

    private static String getPresignedUrl(String fileName) throws IOException {
        String jsonBody = "{\n" +
                "  \"operation\": \"UL_LOGS\",\n" +
                "  \"fileName\": \"" + fileName + "\",\n" +
                "  \"fileType\": \"application/octet-stream\"\n" +
                "}";

        RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));
        Request lambdaRequest = new Request.Builder()
                .url(LAMBDA_URL)
                .post(body)
                .build();

        try (Response lambdaResponse = currentHttpClient.newCall(lambdaRequest).execute()) {

            if (!lambdaResponse.isSuccessful() || lambdaResponse.body() == null) {
                throw new IOException("Failed to get pre-signed URL");
            }

            String responseBody = lambdaResponse.body().string();
            String presignedUrl = responseBody.trim();

            // Case 1: Lambda returned raw URL
            if (presignedUrl.startsWith("http")) {
                return presignedUrl;
            }

            // Case 2: Lambda returned JSON → safely parse
            try {
                JSONObject jsonResponse = new JSONObject(presignedUrl);
                return jsonResponse.getString("url");
            } catch (JSONException e) {
                Log.e(TAG, "JSON parse failed: " + presignedUrl, e);
                throw new IOException("Invalid JSON response from Lambda", e);
            }

        }
    }


    private static boolean uploadToS3(File file, String presignedUrl, long fileSize) throws IOException {
        Log.d("UploadManager", String.format(
                "Starting S3 upload: %s (%.2f MB)",
                file.getName(),
                fileSize / (1024.0 * 1024.0)
        ));

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
                Log.i("UploadManager", String.format(
                        "S3 upload completed in %.1f seconds (HTTP %d)",
                        uploadTime / 1000.0,
                        s3Response.code()
                ));
                return true;
            } else {
                String errorBody = s3Response.body() != null ? s3Response.body().string() : "No response body";
                Log.e("UploadManager", String.format(
                        "S3 upload failed: HTTP %d %s | Error: %s",
                        s3Response.code(),
                        s3Response.message(),
                        errorBody
                ));
                return false;
            }
        }
    }


    private static void updateRecordFailure(FileUploadRecord record, FileUploadDao dao, String reason) {
        record.status = "failed";
        record.failureReason = reason;
        record.timestamp = System.currentTimeMillis();
        dao.update(record);
    }

    private static String determineCategory(String fileName) {
        if (fileName.contains("boot_session") || fileName.contains("startup")) return "boot_logs";
        if (fileName.contains("error") || fileName.contains("crash")) return "error_logs";
        if (fileName.contains("sensor") || fileName.contains("telemetry")) return "sensor_data";
        return "general";
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

        return currentHttpClient.newBuilder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(writeTimeoutSec, TimeUnit.SECONDS)
                .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
                .callTimeout(callTimeoutSec, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }


    // ===================== Thomb LOGS UPLOAD (FIFO) =====================

    public static void processThombLogs(Context context) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                Log.i(TAG, "Starting THOMB LOGS upload thread");
                uploadthombLogsSequentially(context);
            } catch (Exception e) {
                Log.e(TAG, "Error in thomb log upload thread", e);
            }
        });
    }

    private static void uploadthombLogsSequentially(Context context) {
        Log.i(TAG, "====== THOMB LOG UPLOAD STARTED ======");
        Log.i(TAG, "Attempting to access directory: " + THOMB_LOGS_DIR);

        File rideLogsDir = new File(THOMB_LOGS_DIR);

        // ✅ ENHANCED LOGGING - Check directory existence
        if (!rideLogsDir.exists()) {
            Log.e(TAG, "THOMB LOGS DIRECTORY DOES NOT EXIST: " + THOMB_LOGS_DIR);
            Log.e(TAG, "Please verify the directory path is correct");
            return;
        } else {
            Log.i(TAG, "✅ Thomb log directory exists");
        }

        // ✅ ENHANCED LOGGING - Check directory readability
        if (!rideLogsDir.canRead()) {
            Log.e(TAG, " THOMB LOGS DIRECTORY NOT READABLE: " + THOMB_LOGS_DIR);
            Log.e(TAG, "Permission issue - check SELinux or file permissions");
            return;
        } else {
            Log.i(TAG, " Thomb logs directory is readable");
        }

        // ✅ ENHANCED LOGGING - Check if it's actually a directory
        if (!rideLogsDir.isDirectory()) {
            Log.e(TAG, " PATH EXISTS BUT IS NOT A DIRECTORY: " + THOMB_LOGS_DIR);
            return;
        } else {
            Log.i(TAG, " Confirmed it is a directory");
        }

        AppDatabase db = AppDatabase.getInstance(context);
        FileUploadDao dao = db.fileUploadDao();

        List<File> thombLogFiles = new ArrayList<>();
        collectFilesRecursively(rideLogsDir, thombLogFiles);

        if (thombLogFiles.isEmpty()) {
            Log.w(TAG, "No files found in " + THOMB_LOGS_DIR);
            return;
        }

        Log.i(TAG, "Found " + thombLogFiles.size() + " thomb log file(s)");
        for (File f : thombLogFiles) {
            Log.d(TAG, "  - " + f.getName() + " (" + f.length() + " bytes)");
        }

        // ✅ Filter ride log files with enhanced logging
//        for (File file : files) {
//            if (file.isFile() && file.getName().matches("ride_log\\.txt\\.\\d+")) {
//                rideLogFiles.add(file);
//                Log.i(TAG, " MATCHED ride log file: " + file.getName());
//            } else {
//                Log.d(TAG, "   Skipped (not matching pattern): " + file.getName());
//            }
//        }

//        if (rideLogFiles.isEmpty()) {
//            Log.w(TAG, "⚠ No files matching 'ride_log.txt.X' pattern found");
//            Log.w(TAG, "Expected pattern: ride_log.txt.1, ride_log.txt.2, etc.");
//            return;
//        }

        // ✅ ENHANCED LOGGING - Sort and show order
//        rideLogFiles.sort((f1, f2) -> {
//            int num1 = extractRideLogNumber(f1.getName());
//            int num2 = extractRideLogNumber(f2.getName());
//            return Integer.compare(num1, num2);
//        });

        Log.i(TAG, "====== UPLOAD ORDER (FIFO) ======");
        for (int i = 0; i < thombLogFiles.size(); i++) {
            File f = thombLogFiles.get(i);
            Log.i(TAG, String.format("  %d. %s (%.2f KB)",
                    i + 1,
                    f.getName(),
                    f.length() / 1024.0
            ));
        }
        Log.i(TAG, "==================================");

        // ✅ Process each file sequentially
        int successCount = 0;
        int failCount = 0;
        int skipCount = 0;

        for (File file : thombLogFiles) {

            Log.i(TAG, "");
            Log.i(TAG, ">>> Processing: " + file.getName() + " <<<");

            if (!file.exists()) {
                Log.e(TAG, " File disappeared: " + file.getName());
                skipCount++;
                continue;
            }

            if (!file.canRead()) {
                Log.e(TAG, " File not readable: " + file.getName());
                skipCount++;
                continue;
            }

            if (file.length() == 0) {
                Log.w(TAG, "⚠File is empty (0 bytes): " + file.getName());
                skipCount++;
                continue;
            }

            Log.i(TAG, "File size: " + String.format("%.2f KB (%.2f MB)",
                    file.length() / 1024.0,
                    file.length() / (1024.0 * 1024.0)
            ));

            FileUploadRecord record = dao.getRecordByFileName(file.getName());

            if (record == null) {
                int next = dao.getHighestSrNo() + 1;
                record = new FileUploadRecord(
                        file.getName(),
                        next,
                        "thomb",
                        "pending",
                        null,
                        System.currentTimeMillis()
                );
                dao.insert(record);
                Log.i(TAG, "Created new DB record with srNo: " + next);
            } else {
                Log.i(TAG, "Found existing DB record - Status: " + record.status);
            }

            if ("success".equals(record.status)) {
                Log.i(TAG, "Already uploaded previously - deleting: " + file.getName());
                if (file.delete()) {
                    Log.i(TAG, " Deleted already-uploaded file");
                    skipCount++;
                } else {
                    Log.w(TAG, "⚠Failed to delete already-uploaded file");
                }
                continue;
            }

            Log.i(TAG, "Starting upload for: " + file.getName());
            boolean uploadSuccess = uploadThombLogFile(context, file, record, dao);

            if (uploadSuccess) {
                Log.i(TAG, "UPLOAD SUCCESsS: " + file.getName());
                successCount++;

                if (file.delete()) {
                    Log.i(TAG, " File deleted after successful upload");

                    dao.delete(record);
                } else {
                    Log.e(TAG, " FAILED TO DELETE after upload: " + file.getName());
                }
            } else {
                Log.e(TAG, " UPLOAD FAILED: " + file.getName());
//                Log.e(TAG, "STOPPING FIFO PROCESS (remaining files will not be processed)");
                failCount++;
                //break;
            }

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Log.w(TAG, "Sleep interrupted", e);
            }
        }

        Log.i(TAG, "");
        Log.i(TAG, "====== THOMB LOGS UPLOAD SUMMARY ======");
        Log.i(TAG, "Total files found: " + thombLogFiles.size());
        Log.i(TAG, "Successfully uploaded: " + successCount);
        Log.i(TAG, "Failed uploads: " + failCount);
        Log.i(TAG, "Skipped/Already done: " + skipCount);
        Log.i(TAG, "======================================");
    }

//    private static int extractRideLogNumber(String filename) {
//        try {
//            // ride_log.txt.5 -> extract "5"
//            String[] parts = filename.split("\\.");
//            if (parts.length >= 3) {
//                return Integer.parseInt(parts[parts.length - 1]);
//            }
//        } catch (Exception e) {
//            Log.w(TAG, "Failed to extract number from: " + filename, e);
//        }
//        return Integer.MAX_VALUE; // Put invalid files at the end
//    }
    private static boolean uploadThombLogFile(Context context,
                                              File file,
                                              FileUploadRecord record,
                                              FileUploadDao dao) {
        try {
            String imei = DeviceSession.getImei();
            if (imei == null || imei.isEmpty()) {
                Log.w(TAG, "IMEI is null or empty - using 'unknown'");
                imei = "unknown";
            } else {
                Log.d(TAG, "IMEI: " + imei);
            }

            SimpleDateFormat outerFmt = new SimpleDateFormat("dd-MM-yyyy", Locale.US);
            String outerDate = outerFmt.format(new Date());

            SimpleDateFormat innerFmt = new SimpleDateFormat("yyyy_MM_dd", Locale.US);
            String innerDate = innerFmt.format(new Date());

            String s3Key = String.format(
                    "android_hv/vcu_logs_%s/%s/%s/Thomblogs/%s",
                    imei,
                    outerDate,
                    innerDate,
                    file.getName()
            );

            Log.i(TAG, "S3 Path: " + s3Key);
            Log.i(TAG, "Requesting presigned URL from Lambda...");

            String presignedUrl = getPresignedUrl(s3Key);

            Log.i(TAG, " Got presigned URL");
            Log.i(TAG, "Starting S3 upload...");

            boolean success = uploadToS3(file, presignedUrl, file.length());

            if (success) {
                Log.i(TAG, "S3 upload completed successfully");
                record.status = "success";
                record.failureReason = null;
                record.timestamp = System.currentTimeMillis();
                dao.update(record);
                Log.i(TAG, " Database record updated to 'success'");
                return true;
            } else {
                Log.e(TAG, " S3 upload failed");
                record.status = "failed";
                record.failureReason = "S3 upload failed";
                record.timestamp = System.currentTimeMillis();
                dao.update(record);
                Log.e(TAG, " Database record updated to 'failed'");
                return false;
            }

        } catch (Exception e) {
            Log.e(TAG, " EXCEPTION during thomb log upload: " + file.getName(), e);
            Log.e(TAG, "Exception type: " + e.getClass().getName());
            Log.e(TAG, "Exception message: " + e.getMessage());
            updateRecordFailure(record, dao, e.getMessage());
            return false;
        }
    }

    // ===================== DEBUG LOGS UPLOAD (SEPARATE THREAD) =====================

    public static void processDebugLogs(Context context) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                Log.i(TAG, "Starting DEBUG LOGS upload thread");
                uploadDebugLogsSequentially(context);
            } catch (Exception e) {
                Log.e(TAG, "Error in debug logs upload thread", e);
            }
        });
    }

    private static void uploadDebugLogsSequentially(Context context) {
        Log.i(TAG, "====== DEBUG LOGS UPLOAD STARTED ======");
        Log.i(TAG, "Attempting to access directory: " + DEBUG_LOGS_DIR);

        File debugLogsDir = new File(DEBUG_LOGS_DIR);

        if (!debugLogsDir.exists()) {
            Log.e(TAG, "DEBUG LOGS DIRECTORY DOES NOT EXIST: " + DEBUG_LOGS_DIR);
            return;
        }

        if (!debugLogsDir.canRead()) {
            Log.e(TAG, "DEBUG LOGS DIRECTORY NOT READABLE: " + DEBUG_LOGS_DIR);
            return;
        }

        if (!debugLogsDir.isDirectory()) {
            Log.e(TAG, "PATH EXISTS BUT IS NOT A DIRECTORY: " + DEBUG_LOGS_DIR);
            return;
        }

        Log.i(TAG, " Debug logs directory exists and is readable");

        AppDatabase db = AppDatabase.getInstance(context);
        FileUploadDao dao = db.fileUploadDao();

        List<File> debugLogFiles = new ArrayList<>();
        collectFilesRecursively(debugLogsDir, debugLogFiles);

        if (debugLogFiles.isEmpty()) {
            Log.w(TAG, "No files found in " + DEBUG_LOGS_DIR);
            return;
        }

        Log.i(TAG, "Found " + debugLogFiles.size() + " debug log file(s)");

        int successCount = 0;
        int failCount = 0;
        int skipCount = 0;

        for (File file : debugLogFiles) {

            if (!file.isFile() || !file.canRead() || file.length() == 0) {
                Log.d(TAG, "Skipping empty/unreadable file: " + file.getName());
                skipCount++;
                continue;
            }

            Log.i(TAG, "Processing debug log: " + file.getName() + " (size: "
                    + String.format("%.2f KB", file.length() / 1024.0) + ")");

            FileUploadRecord record = dao.getRecordByFileName(file.getName());

            if (record == null) {
                int next = dao.getHighestSrNo() + 1;
                record = new FileUploadRecord(
                        file.getName(),
                        next,
                        "debug",
                        "pending",
                        null,
                        System.currentTimeMillis()
                );
                dao.insert(record);
                Log.i(TAG, "Created new DB record for debug log");
            }

            if ("success".equals(record.status)) {
                Log.i(TAG, "Already uploaded - deleting: " + file.getName());
                if (file.delete()) {
                    Log.i(TAG, "Deleted already-uploaded debug log");
                    skipCount++;
                } else {
                    Log.w(TAG, " Failed to delete already-uploaded file");
                }
                continue;
            }

            boolean uploadSuccess = uploadDebugLogFile(context, file, record, dao);

            if (uploadSuccess) {
                Log.i(TAG, " UPLOAD SUCCESS: " + file.getName());
                successCount++;

                // Delete after successful upload
                if (file.delete()) {
                    Log.i(TAG, " File deleted after successful upload");
                    dao.delete(record);
                } else {
                    Log.e(TAG, " FAILED TO DELETE after upload: " + file.getName());
                }
            } else {
                Log.e(TAG, "UPLOAD FAILED: " + file.getName());
                failCount++;
            }

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Log.w(TAG, "Sleep interrupted", e);
            }
        }

        Log.i(TAG, "");
        Log.i(TAG, "====== DEBUG LOGS UPLOAD SUMMARY ======");
        Log.i(TAG, "Total files found: " + debugLogFiles.size());
        Log.i(TAG, "Successfully uploaded: " + successCount);
        Log.i(TAG, "Failed uploads: " + failCount);
        Log.i(TAG, "Skipped/Already done: " + skipCount);
        Log.i(TAG, "=======================================");
    }

    private static boolean uploadDebugLogFile(Context context,
                                              File file,
                                              FileUploadRecord record,
                                              FileUploadDao dao) {
        try {
            String imei = DeviceSession.getImei();
            if (imei == null || imei.isEmpty()) {
                imei = "unknown";
            }

            SimpleDateFormat outerFmt = new SimpleDateFormat("dd-MM-yyyy", Locale.US);
            String outerDate = outerFmt.format(new Date());

            SimpleDateFormat innerFmt = new SimpleDateFormat("yyyy_MM_dd", Locale.US);
            String innerDate = innerFmt.format(new Date());

            // Same structure as dropbox: android_hv/vcu_logs_IMEI/dd-MM-yyyy/yyyy_MM_dd/debug/filename
            String s3Key = String.format(
                    "android_hv/vcu_logs_%s/%s/%s/debug/%s",
                    imei,
                    outerDate,
                    innerDate,
                    file.getName()
            );

            Log.i(TAG, "Debug log S3 Path: " + s3Key);

            String presignedUrl = getPresignedUrl(s3Key);
            boolean success = uploadToS3(file, presignedUrl, file.length());

            if (success) {
                record.status = "success";
                record.failureReason = null;
                record.timestamp = System.currentTimeMillis();
                dao.update(record);
                return true;
            } else {
                record.status = "failed";
                record.failureReason = "S3 upload failed";
                record.timestamp = System.currentTimeMillis();
                dao.update(record);
                return false;
            }

        } catch (Exception e) {
            Log.e(TAG, "Exception during debug log upload: " + file.getName(), e);
            updateRecordFailure(record, dao, e.getMessage());
            return false;
        }
    }

    // ===================== ANR LOGS UPLOAD (SEPARATE THREAD) =====================

    public static void processAnrLogs(Context context) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                Log.i(TAG, "Starting ANR LOGS upload thread");
                uploadAnrLogsSequentially(context);
            } catch (Exception e) {
                Log.e(TAG, "Error in ANR logs upload thread", e);
            }
        });
    }

    private static void uploadAnrLogsSequentially(Context context) {
        Log.i(TAG, "====== ANR LOGS UPLOAD STARTED ======");
        Log.i(TAG, "Attempting to access directory: " + ANR_LOGS_DIR);

        File anrLogsDir = new File(ANR_LOGS_DIR);

        if (!anrLogsDir.exists()) {
            Log.e(TAG, "ANR LOGS DIRECTORY DOES NOT EXIST: " + ANR_LOGS_DIR);
            return;
        }

        if (!anrLogsDir.canRead()) {
            Log.e(TAG, "ANR LOGS DIRECTORY NOT READABLE: " + ANR_LOGS_DIR);
            return;
        }

        if (!anrLogsDir.isDirectory()) {
            Log.e(TAG, "PATH EXISTS BUT IS NOT A DIRECTORY: " + ANR_LOGS_DIR);
            return;
        }

        Log.i(TAG, "✅ ANR logs directory exists and is readable");

        AppDatabase db = AppDatabase.getInstance(context);
        FileUploadDao dao = db.fileUploadDao();

        List<File> anrLogFiles = new ArrayList<>();
        collectFilesRecursively(anrLogsDir, anrLogFiles);

        if (anrLogFiles.isEmpty()) {
            Log.w(TAG, "No files found in " + ANR_LOGS_DIR);
            return;
        }

        Log.i(TAG, "Found " + anrLogFiles.size() + " ANR log file(s)");

        int successCount = 0;
        int failCount = 0;
        int skipCount = 0;

        for (File file : anrLogFiles) {

            if (!file.isFile() || !file.canRead() || file.length() == 0) {
                Log.d(TAG, "Skipping empty/unreadable file: " + file.getName());
                skipCount++;
                continue;
            }

            Log.i(TAG, "Processing ANR log: " + file.getName() + " (size: "
                    + String.format("%.2f KB", file.length() / 1024.0) + ")");

            FileUploadRecord record = dao.getRecordByFileName(file.getName());

            if (record == null) {
                int next = dao.getHighestSrNo() + 1;
                record = new FileUploadRecord(
                        file.getName(),
                        next,
                        "anr",
                        "pending",
                        null,
                        System.currentTimeMillis()
                );
                dao.insert(record);
                Log.i(TAG, "Created new DB record for ANR log");
            }

            if ("success".equals(record.status)) {
                Log.i(TAG, "Already uploaded - deleting: " + file.getName());
                if (file.delete()) {
                    Log.i(TAG, "✅ Deleted already-uploaded ANR log");
                    skipCount++;
                } else {
                    Log.w(TAG, "⚠️ Failed to delete already-uploaded file");
                }
                continue;
            }

            boolean uploadSuccess = uploadAnrLogFile(context, file, record, dao);

            if (uploadSuccess) {
                Log.i(TAG, "✅ UPLOAD SUCCESS: " + file.getName());
                successCount++;

                // Delete after successful upload
                if (file.delete()) {
                    Log.i(TAG, "✅ File deleted after successful upload");
                    dao.delete(record);
                } else {
                    Log.e(TAG, "❌ FAILED TO DELETE after upload: " + file.getName());
                }
            } else {
                Log.e(TAG, "❌ UPLOAD FAILED: " + file.getName());
                failCount++;
            }

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Log.w(TAG, "Sleep interrupted", e);
            }
        }

        Log.i(TAG, "");
        Log.i(TAG, "====== ANR LOGS UPLOAD SUMMARY ======");
        Log.i(TAG, "Total files found: " + anrLogFiles.size());
        Log.i(TAG, "Successfully uploaded: " + successCount);
        Log.i(TAG, "Failed uploads: " + failCount);
        Log.i(TAG, "Skipped/Already done: " + skipCount);
        Log.i(TAG, "======================================");
    }

    private static boolean uploadAnrLogFile(Context context,
                                            File file,
                                            FileUploadRecord record,
                                            FileUploadDao dao) {
        try {
            String imei = DeviceSession.getImei();
            if (imei == null || imei.isEmpty()) {
                imei = "unknown";
            }

            SimpleDateFormat outerFmt = new SimpleDateFormat("dd-MM-yyyy", Locale.US);
            String outerDate = outerFmt.format(new Date());

            SimpleDateFormat innerFmt = new SimpleDateFormat("yyyy_MM_dd", Locale.US);
            String innerDate = innerFmt.format(new Date());

            // Same structure as dropbox: android_hv/vcu_logs_IMEI/dd-MM-yyyy/yyyy_MM_dd/anr/filename
            String s3Key = String.format(
                    "android_hv/vcu_logs_%s/%s/%s/anr/%s",
                    imei,
                    outerDate,
                    innerDate,
                    file.getName()
            );

            Log.i(TAG, "ANR log S3 Path: " + s3Key);

            String presignedUrl = getPresignedUrl(s3Key);
            boolean success = uploadToS3(file, presignedUrl, file.length());

            if (success) {
                record.status = "success";
                record.failureReason = null;
                record.timestamp = System.currentTimeMillis();
                dao.update(record);
                return true;
            } else {
                record.status = "failed";
                record.failureReason = "S3 upload failed";
                record.timestamp = System.currentTimeMillis();
                dao.update(record);
                return false;
            }

        } catch (Exception e) {
            Log.e(TAG, "Exception during ANR log upload: " + file.getName(), e);
            updateRecordFailure(record, dao, e.getMessage());
            return false;
        }
    }

}


