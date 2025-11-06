//package com.example.database.service;
//
//import android.content.Context;
//import com.example.database.db.AppDatabase;
//import com.example.database.db.FileUploadDao;
//import com.example.database.db.FileUploadRecord;
//import com.example.database.db.FileDownloadDao;
//import com.example.database.db.FileDownloadRecord;
//import timber.log.Timber;
//
//import okhttp3.*;
//import org.json.JSONArray;
//import org.json.JSONObject;
//
//import java.io.*;
//import java.text.SimpleDateFormat;
//import java.util.Date;
//import java.util.List;
//import java.util.Locale;
//import java.util.concurrent.TimeUnit;
//
//public class CloudDownloader {
//
//    private static final String LAMBDA_URL = "https://ssmfnv8c9h.execute-api.ap-south-1.amazonaws.com/default/GenerateS3UploadUrl";
//
//    private final Context context;
//    private final FileDownloadDao dao;
//    private final OkHttpClient httpClient;
//
//    public CloudDownloader(Context context, FileDownloadDao dao) {
//        this.context = context;
//        this.dao = dao;
//
//        this.httpClient = new OkHttpClient.Builder()
//                .connectTimeout(60, TimeUnit.SECONDS)
//                .readTimeout(300, TimeUnit.SECONDS)
//                .writeTimeout(60, TimeUnit.SECONDS)
//                .build();
//
//        Timber.d("🔧 Created download HTTP client (5 min read timeout)");
//    }
//
//    public void listS3Files() {
//        try {
//            Timber.d("📋 Discovering uploaded files for download...");
//
//            // Get successful uploads from upload database
//            AppDatabase db = AppDatabase.getInstance(context);
//            FileUploadDao uploadDao = db.fileUploadDao();
//
//            // Get all successfully uploaded files
//            List<FileUploadRecord> successfulUploads = uploadDao.getSuccessfulRecords();
//
//            if (successfulUploads.isEmpty()) {
//                Timber.i("📂 No successful uploads found - nothing to download");
//                return;
//            }
//
//            Timber.i("📊 Found %d successfully uploaded files", successfulUploads.size());
//
//            // Add each successful upload to download queue
//            int newDownloads = 0;
//            for (FileUploadRecord upload : successfulUploads) {
//                if (addUploadForDownload(upload)) {
//                    newDownloads++;
//                }
//            }
//
//            Timber.i("📝 Added %d new files to download queue (total successful uploads: %d)",
//                    newDownloads, successfulUploads.size());
//
//        } catch (Exception e) {
//            Timber.e(e, "❌ Error discovering uploaded files for download");
//        }
//    }
//
//    private boolean addUploadForDownload(FileUploadRecord upload) {
//        try {
//            // Check if file already exists in download records
//            FileDownloadRecord existing = dao.getRecordByFileName(upload.fileName);
//            if (existing == null) {
//                // Create new download record based on upload record
//                FileDownloadRecord record = new FileDownloadRecord(
//                        upload.fileName,
//                        getFileSize(upload.fileName),
//                        "pending",
//                        null,
//                        System.currentTimeMillis(),
//                        0
//                );
//                dao.insert(record);
//                Timber.d("📝 Added to download queue: %s", upload.fileName);
//                return true;
//            } else {
//                Timber.d("📝 File already in download queue: %s (status: %s)",
//                        upload.fileName, existing.status);
//                return false;
//            }
//        } catch (Exception e) {
//            Timber.e(e, "❌ Error adding upload to download queue: %s", upload.fileName);
//            return false;
//        }
//    }
//
//    private long getFileSize(String fileName) {
//        try {
//            // Method 1: Check if file still exists in upload queue
//            File uploadQueueDir = new File(context.getFilesDir(), "upload_queue");
//            File uploadFile = new File(uploadQueueDir, fileName);
//            if (uploadFile.exists()) {
//                long size = uploadFile.length();
//                Timber.d("📏 Got file size from upload queue: %s = %d bytes", fileName, size);
//                return size;
//            }
//
//            // Method 2: Check original vendor directory (if accessible)
//            File vendorFile = new File("/data/vendor/udp_socket", fileName);
//            if (vendorFile.exists() && vendorFile.canRead()) {
//                long size = vendorFile.length();
//                Timber.d("📏 Got file size from vendor directory: %s = %d bytes", fileName, size);
//                return size;
//            }
//
//            // Method 3: Estimate based on filename patterns
//            if (fileName.contains("boot_session")) {
//                Timber.d("📏 Estimated large file size for: %s", fileName);
//                return 10 * 1024 * 1024; // 10MB estimate
//            } else {
//                Timber.d("📏 Estimated small file size for: %s", fileName);
//                return 1024 * 1024; // 1MB estimate
//            }
//
//        } catch (Exception e) {
//            Timber.w("⚠️ Could not determine file size for: %s, using 1MB estimate", fileName);
//            return 1024 * 1024; // 1MB default
//        }
//    }
//
//    public void downloadFile(FileDownloadRecord record, File targetFile) {
//        try {
//            long fileSize = record.fileSize;
//            Timber.i("🚀 STARTING DOWNLOAD: %s (%.2f MB)", record.fileName, fileSize / (1024.0 * 1024.0));
//            Timber.d("📊 Download attempt #%d for this file", record.retryCount + 1);
//
//            // Step 1: Get pre-signed download URL
//            Timber.d("🔗 Step 1/2: Requesting download permission from AWS...");
//            String presignedUrl = getPresignedDownloadUrl(record.fileName);
//
//            // Step 2: Download from S3
//            Timber.d("☁️ Step 2/2: Downloading file data from cloud storage...");
//            boolean success = downloadFromS3(presignedUrl, targetFile, fileSize);
//
//            if (success) {
//                // ✅ SUCCESS - Update actual file size
//                long actualSize = targetFile.length();
//                record.status = "completed";
//                record.failureReason = null;
//                record.fileSize = actualSize;  // Update with actual downloaded size
//                record.timestamp = System.currentTimeMillis();
//                dao.update(record);
//
//                Timber.i("✅ DOWNLOAD SUCCESSFUL: %s (%.2f MB actual)", record.fileName, actualSize / (1024.0 * 1024.0));
//                Timber.d("📁 File saved to: %s", targetFile.getAbsolutePath());
//            } else {
//                updateRecordFailure(record, "S3 download failed - server rejected the request");
//            }
//
//        } catch (Exception e) {
//            Timber.w("⚠️ DOWNLOAD INTERRUPTED: %s", e.getMessage());
//
//            String userFriendlyReason;
//            if (e.getMessage().contains("Failed to connect")) {
//                userFriendlyReason = "Network connection issue - please check internet connectivity";
//                Timber.i("🌐 NETWORK ISSUE: Unable to reach AWS servers");
//            } else if (e.getMessage().contains("timeout")) {
//                userFriendlyReason = "Download timed out - file may be too large or connection too slow";
//                Timber.i("⏱️ TIMEOUT: Download took too long to complete");
//            } else {
//                userFriendlyReason = "Unexpected error: " + e.getMessage();
//                Timber.i("❓ UNKNOWN ERROR: %s", e.getMessage());
//            }
//
//            updateRecordFailure(record, userFriendlyReason);
//        }
//    }
//
//    // ✅ FIXED: Handle direct URL response from Lambda
//    private String getPresignedDownloadUrl(String fileName) throws Exception {
//        JSONObject requestBody = new JSONObject();
//        requestBody.put("fileName", fileName);
//        requestBody.put("fileType", "application/octet-stream");
//        requestBody.put("operation", "DOWNLOAD");
//
//        Timber.d("📤 Download Request JSON: %s", requestBody.toString());
//
//        RequestBody body = RequestBody.create(
//                requestBody.toString(),
//                MediaType.parse("application/json")
//        );
//
//        Request request = new Request.Builder()
//                .url(LAMBDA_URL)
//                .post(body)
//                .build();
//
//        try (Response response = httpClient.newCall(request).execute()) {
//            String responseBody = response.body().string();
//
//            if (!response.isSuccessful()) {
//                throw new RuntimeException("Lambda download URL request failed: HTTP " + response.code() + " - " + responseBody);
//            }
//
//            Timber.d("📥 Lambda Response: HTTP %d OK", response.code());
//            Timber.d("📥 Raw Lambda Response: %s", responseBody.substring(0, Math.min(100, responseBody.length())) + "...");
//
//            // ✅ FIXED: Lambda returns direct URL string (not JSON)
//            String presignedUrl = responseBody.trim();
//
//            // Validate URL format
//            if (!presignedUrl.startsWith("https://")) {
//                throw new RuntimeException("Invalid download URL received from Lambda: " + presignedUrl);
//            }
//
//            Timber.d("📥 Received pre-signed download URL for: %s", fileName);
//
//            return presignedUrl;
//        }
//    }
//
//    private boolean downloadFromS3(String presignedUrl, File targetFile, long expectedSize) {
//        try {
//            Request request = new Request.Builder()
//                    .url(presignedUrl)
//                    .get()  // ✅ GET request for download
//                    .build();
//
//            Timber.d("🌐 Executing S3 download request...");
//
//            try (Response response = httpClient.newCall(request).execute()) {
//                if (!response.isSuccessful()) {
//                    Timber.e("❌ S3 download failed: HTTP %d - %s", response.code(), response.message());
//                    return false;
//                }
//
//                Timber.d("📥 S3 Response: HTTP %d OK", response.code());
//
//                try (InputStream inputStream = response.body().byteStream();
//                     FileOutputStream outputStream = new FileOutputStream(targetFile)) {
//
//                    byte[] buffer = new byte[8192];
//                    long totalBytesRead = 0;
//                    int bytesRead;
//                    long lastProgressTime = System.currentTimeMillis();
//
//                    while ((bytesRead = inputStream.read(buffer)) != -1) {
//                        outputStream.write(buffer, 0, bytesRead);
//                        totalBytesRead += bytesRead;
//
//                        long currentTime = System.currentTimeMillis();
//                        if (currentTime - lastProgressTime > 5000) {
//                            if (expectedSize > 0) {
//                                double progressPercent = (totalBytesRead * 100.0) / expectedSize;
//                                Timber.d("📈 Download progress: %.1f%% (%.2f MB / %.2f MB)",
//                                        progressPercent,
//                                        totalBytesRead / (1024.0 * 1024.0),
//                                        expectedSize / (1024.0 * 1024.0));
//                            } else {
//                                Timber.d("📈 Downloaded: %.2f MB", totalBytesRead / (1024.0 * 1024.0));
//                            }
//                            lastProgressTime = currentTime;
//                        }
//                    }
//
//                    outputStream.flush();
//                    outputStream.getFD().sync();
//
//                    Timber.d("💾 Downloaded %d bytes to: %s", totalBytesRead, targetFile.getName());
//                    return true;
//                }
//            }
//
//        } catch (Exception e) {
//            Timber.e(e, "❌ S3 download exception");
//            return false;
//        }
//    }
//
//    private void updateRecordFailure(FileDownloadRecord record, String reason) {
//        record.status = "failed";
//        record.failureReason = reason;
//        record.timestamp = System.currentTimeMillis();
//        record.retryCount++;
//        dao.update(record);
//
//        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
//        String currentTime = sdf.format(new Date(record.timestamp));
//        String retryTime = sdf.format(new Date(System.currentTimeMillis() + (30 * 60 * 1000)));
//
//        Timber.w("❌ Download FAILED for file: %s at %s", record.fileName, currentTime);
//        Timber.w("📝 Failure reason: %s", reason);
//        Timber.i("🔄 RETRY SCHEDULED: Will attempt download again in 30 minutes");
//        Timber.i("⏰ Next retry time: %s", retryTime);
//    }
//}





























//package com.example.database.service;
//
//import android.content.Context;
//import com.example.database.db.AppDatabase;
//import com.example.database.db.FileUploadDao;
//import com.example.database.db.FileUploadRecord;
//import com.example.database.db.FileDownloadDao;
//import com.example.database.db.FileDownloadRecord;
//import timber.log.Timber;
//
//import okhttp3.*;
//import org.json.JSONObject;
//
//import java.io.*;
//import java.text.SimpleDateFormat;
//import java.util.Date;
//import java.util.List;
//import java.util.Locale;
//import java.util.concurrent.TimeUnit;
//
//public class CloudDownloader {
//
//    // ✅ UPDATED: Same Lambda URL as upload
//    private static final String LAMBDA_URL = "https://bpfsuu5xvj.execute-api.ap-south-1.amazonaws.com/default/s3uploadurlcreatorv1-dev-getPreSignedURLToPutToS3-dev";
//
//    private final Context context;
//    private final FileDownloadDao dao;
//    private final OkHttpClient httpClient;
//
//    public CloudDownloader(Context context, FileDownloadDao dao) {
//        this.context = context;
//        this.dao = dao;
//
//        this.httpClient = new OkHttpClient.Builder()
//                .connectTimeout(60, TimeUnit.SECONDS)
//                .readTimeout(300, TimeUnit.SECONDS)
//                .writeTimeout(60, TimeUnit.SECONDS)
//                .build();
//
//        Timber.d("🔧 Created download HTTP client (5 min read timeout)");
//    }
//
//    public void listS3Files() {
//        try {
//            Timber.d("📋 Discovering uploaded files for download...");
//
//            // Get successful uploads from upload database
//            AppDatabase db = AppDatabase.getInstance(context);
//            FileUploadDao uploadDao = db.fileUploadDao();
//
//            // Get all successfully uploaded files
//            List<FileUploadRecord> successfulUploads = uploadDao.getSuccessfulRecords();
//
//            if (successfulUploads.isEmpty()) {
//                Timber.i("📂 No successful uploads found - nothing to download");
//                return;
//            }
//
//            Timber.i("📊 Found %d successfully uploaded files", successfulUploads.size());
//
//            // Add each successful upload to download queue
//            int newDownloads = 0;
//            for (FileUploadRecord upload : successfulUploads) {
//                if (addUploadForDownload(upload)) {
//                    newDownloads++;
//                }
//            }
//
//            Timber.i("📝 Added %d new files to download queue (total successful uploads: %d)",
//                    newDownloads, successfulUploads.size());
//
//        } catch (Exception e) {
//            Timber.e(e, "❌ Error discovering uploaded files for download");
//        }
//    }
//
//    private boolean addUploadForDownload(FileUploadRecord upload) {
//        try {
//            // Check if file already exists in download records
//            FileDownloadRecord existing = dao.getRecordByFileName(upload.fileName);
//            if (existing == null) {
//                // Create new download record based on upload record
//                FileDownloadRecord record = new FileDownloadRecord(
//                        upload.fileName,
//                        getFileSize(upload.fileName),
//                        "pending",
//                        null,
//                        System.currentTimeMillis(),
//                        0
//                );
//                dao.insert(record);
//                Timber.d("📝 Added to download queue: %s", upload.fileName);
//                return true;
//            } else {
//                Timber.d("📝 File already in download queue: %s (status: %s)",
//                        upload.fileName, existing.status);
//                return false;
//            }
//        } catch (Exception e) {
//            Timber.e(e, "❌ Error adding upload to download queue: %s", upload.fileName);
//            return false;
//        }
//    }
//
//    private long getFileSize(String fileName) {
//        try {
//            // Method 1: Check if file still exists in upload queue
//            File uploadQueueDir = new File(context.getFilesDir(), "upload_queue");
//            File uploadFile = new File(uploadQueueDir, fileName);
//            if (uploadFile.exists()) {
//                long size = uploadFile.length();
//                Timber.d("📏 Got file size from upload queue: %s = %d bytes", fileName, size);
//                return size;
//            }
//
//            // Method 2: Check original vendor directory (if accessible)
//            File vendorFile = new File("/data/vendor/udp_socket", fileName);
//            if (vendorFile.exists() && vendorFile.canRead()) {
//                long size = vendorFile.length();
//                Timber.d("📏 Got file size from vendor directory: %s = %d bytes", fileName, size);
//                return size;
//            }
//
//            // Method 3: Estimate based on filename patterns
//            if (fileName.contains("boot_session")) {
//                Timber.d("📏 Estimated large file size for: %s", fileName);
//                return 10 * 1024 * 1024; // 10MB estimate
//            } else {
//                Timber.d("📏 Estimated small file size for: %s", fileName);
//                return 1024 * 1024; // 1MB estimate
//            }
//
//        } catch (Exception e) {
//            Timber.w("⚠️ Could not determine file size for: %s, using 1MB estimate", fileName);
//            return 1024 * 1024; // 1MB default
//        }
//    }
//
//    public void downloadFile(FileDownloadRecord record, File targetFile) {
//        try {
//            long fileSize = record.fileSize;
//            Timber.i("🚀 STARTING DOWNLOAD: %s (%.2f MB)", record.fileName, fileSize / (1024.0 * 1024.0));
//            Timber.d("📊 Download attempt #%d for this file", record.retryCount + 1);
//
//            // Step 1: Get pre-signed download URL
//            Timber.d("🔗 Step 1/2: Requesting download permission from AWS...");
//            String presignedUrl = getPresignedDownloadUrl(record.fileName);
//
//            // Step 2: Download from S3
//            Timber.d("☁️ Step 2/2: Downloading file data from cloud storage...");
//            boolean success = downloadFromS3(presignedUrl, targetFile, fileSize);
//
//            if (success) {
//                // ✅ SUCCESS - Update actual file size
//                long actualSize = targetFile.length();
//                record.status = "completed";
//                record.failureReason = null;
//                record.fileSize = actualSize;  // Update with actual downloaded size
//                record.timestamp = System.currentTimeMillis();
//                dao.update(record);
//
//                Timber.i("✅ DOWNLOAD SUCCESSFUL: %s (%.2f MB actual)", record.fileName, actualSize / (1024.0 * 1024.0));
//                Timber.d("📁 File saved to: %s", targetFile.getAbsolutePath());
//            } else {
//                updateRecordFailure(record, "S3 download failed - server rejected the request");
//            }
//
//        } catch (Exception e) {
//            Timber.w("⚠️ DOWNLOAD INTERRUPTED: %s", e.getMessage());
//
//            String userFriendlyReason;
//            if (e.getMessage().contains("Failed to connect")) {
//                userFriendlyReason = "Network connection issue - please check internet connectivity";
//                Timber.i("🌐 NETWORK ISSUE: Unable to reach AWS servers");
//            } else if (e.getMessage().contains("timeout")) {
//                userFriendlyReason = "Download timed out - file may be too large or connection too slow";
//                Timber.i("⏱️ TIMEOUT: Download took too long to complete");
//            } else {
//                userFriendlyReason = "Unexpected error: " + e.getMessage();
//                Timber.i("❓ UNKNOWN ERROR: %s", e.getMessage());
//            }
//
//            updateRecordFailure(record, userFriendlyReason);
//        }
//    }
//
//    // ✅ UPDATED: New Lambda URL and proper JSON body + response parsing
//    private String getPresignedDownloadUrl(String fileName) throws Exception {
//        // ✅ JSON body for download operation
//        String jsonBody = "{\n" +
//                "  \"operation\": \"DL_FILE_EX\",\n" +
//                "  \"fileName\": \"" + fileName + "\",\n" +
//                "  \"fileType\": \"application/octet-stream\"\n" +
//                "}";
//
//        Timber.d("📤 Download Request JSON: %s", jsonBody);
//
//        RequestBody body = RequestBody.create(
//                jsonBody,
//                MediaType.parse("application/json")
//        );
//
//        Request request = new Request.Builder()
//                .url(LAMBDA_URL)
//                .post(body)
//                .build();
//
//        try (Response response = httpClient.newCall(request).execute()) {
//            if (!response.isSuccessful() || response.body() == null) {
//                throw new RuntimeException("Lambda download URL request failed: HTTP " + response.code());
//            }
//
//            String responseBody = response.body().string();
//            Timber.d("📥 Lambda Response: HTTP %d OK", response.code());
//            Timber.d("📥 Raw Response: %s", responseBody.substring(0, Math.min(100, responseBody.length())) + "...");
//
//            // ✅ Parse JSON response to extract "url" field
//            try {
//                JSONObject jsonResponse = new JSONObject(responseBody);
//                String presignedUrl = jsonResponse.getString("url");
//                String method = jsonResponse.optString("method", "GET");
//
//                Timber.d("✅ Extracted download URL (method: %s)", method);
//
//                // Validate URL format
//                if (!presignedUrl.startsWith("https://")) {
//                    throw new RuntimeException("Invalid download URL received");
//                }
//
//                return presignedUrl;
//
//            } catch (Exception e) {
//                Timber.e(e, "❌ Failed to parse JSON response");
//                throw new RuntimeException("Failed to parse download URL from response: " + e.getMessage());
//            }
//        }
//    }
//
//    private boolean downloadFromS3(String presignedUrl, File targetFile, long expectedSize) {
//        try {
//            Request request = new Request.Builder()
//                    .url(presignedUrl)
//                    .get()  // ✅ GET request for download
//                    .build();
//
//            Timber.d("🌐 Executing S3 download request...");
//
//            try (Response response = httpClient.newCall(request).execute()) {
//                if (!response.isSuccessful()) {
//                    Timber.e("❌ S3 download failed: HTTP %d - %s", response.code(), response.message());
//                    return false;
//                }
//
//                Timber.d("📥 S3 Response: HTTP %d OK", response.code());
//
//                try (InputStream inputStream = response.body().byteStream();
//                     FileOutputStream outputStream = new FileOutputStream(targetFile)) {
//
//                    byte[] buffer = new byte[8192];
//                    long totalBytesRead = 0;
//                    int bytesRead;
//                    long lastProgressTime = System.currentTimeMillis();
//
//                    while ((bytesRead = inputStream.read(buffer)) != -1) {
//                        outputStream.write(buffer, 0, bytesRead);
//                        totalBytesRead += bytesRead;
//
//                        long currentTime = System.currentTimeMillis();
//                        if (currentTime - lastProgressTime > 5000) {
//                            if (expectedSize > 0) {
//                                double progressPercent = (totalBytesRead * 100.0) / expectedSize;
//                                Timber.d("📈 Download progress: %.1f%% (%.2f MB / %.2f MB)",
//                                        progressPercent,
//                                        totalBytesRead / (1024.0 * 1024.0),
//                                        expectedSize / (1024.0 * 1024.0));
//                            } else {
//                                Timber.d("📈 Downloaded: %.2f MB", totalBytesRead / (1024.0 * 1024.0));
//                            }
//                            lastProgressTime = currentTime;
//                        }
//                    }
//
//                    outputStream.flush();
//                    outputStream.getFD().sync();
//
//                    Timber.d("💾 Downloaded %d bytes to: %s", totalBytesRead, targetFile.getName());
//                    return true;
//                }
//            }
//
//        } catch (Exception e) {
//            Timber.e(e, "❌ S3 download exception");
//            return false;
//        }
//    }
//
//    private void updateRecordFailure(FileDownloadRecord record, String reason) {
//        record.status = "failed";
//        record.failureReason = reason;
//        record.timestamp = System.currentTimeMillis();
//        record.retryCount++;
//        dao.update(record);
//
//        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
//        String currentTime = sdf.format(new Date(record.timestamp));
//        String retryTime = sdf.format(new Date(System.currentTimeMillis() + (30 * 60 * 1000)));
//
//        Timber.w("❌ Download FAILED for file: %s at %s", record.fileName, currentTime);
//        Timber.w("📝 Failure reason: %s", reason);
//        Timber.i("🔄 RETRY SCHEDULED: Will attempt download again in 30 minutes");
//        Timber.i("⏰ Next retry time: %s", retryTime);
//    }
//}















//
//
//
//
//
//package com.example.database.service;
//
//import android.content.Context;
//import com.example.database.db.AppDatabase;
//import com.example.database.db.FileUploadDao;
//import com.example.database.db.FileUploadRecord;
//import com.example.database.db.FileDownloadDao;
//import com.example.database.db.FileDownloadRecord;
//import timber.log.Timber;
//
//import okhttp3.*;
//import org.json.JSONObject;
//
//import java.io.*;
//import java.text.SimpleDateFormat;
//import java.util.Date;
//import java.util.List;
//import java.util.Locale;
//import java.util.concurrent.TimeUnit;
//
//public class CloudDownloader {
//
//    private static final String LAMBDA_URL = "https://bpfsuu5xvj.execute-api.ap-south-1.amazonaws.com/default/s3uploadurlcreatorv1-dev-getPreSignedURLToPutToS3-dev";
//
//    private final Context context;
//    private final FileDownloadDao dao;
//    private final OkHttpClient httpClient;
//
//    public CloudDownloader(Context context, FileDownloadDao dao) {
//        this.context = context;
//        this.dao = dao;
//
//        this.httpClient = new OkHttpClient.Builder()
//                .connectTimeout(60, TimeUnit.SECONDS)
//                .readTimeout(300, TimeUnit.SECONDS)
//                .writeTimeout(60, TimeUnit.SECONDS)
//                .build();
//
//        Timber.d("Created download HTTP client (5 min read timeout)");
//    }
//
//    public void listS3Files() {
//        try {
//            Timber.d("Discovering uploaded files for download...");
//
//            AppDatabase db = AppDatabase.getInstance(context);
//            FileUploadDao uploadDao = db.fileUploadDao();
//
//            List<FileUploadRecord> successfulUploads = uploadDao.getSuccessfulRecords();
//
//            if (successfulUploads.isEmpty()) {
//                Timber.i("No successful uploads found - nothing to download");
//                return;
//            }
//
//            Timber.i("Found %d successfully uploaded files", successfulUploads.size());
//
//            int newDownloads = 0;
//            for (FileUploadRecord upload : successfulUploads) {
//                if (addUploadForDownload(upload)) {
//                    newDownloads++;
//                }
//            }
//
//            Timber.i("Added %d new files to download queue (total successful uploads: %d)",
//                    newDownloads, successfulUploads.size());
//
//        } catch (Exception e) {
//            Timber.e(e, "Error discovering uploaded files for download");
//        }
//    }
//
//    private boolean addUploadForDownload(FileUploadRecord upload) {
//        try {
//            FileDownloadRecord existing = dao.getRecordByFileName(upload.fileName);
//            if (existing == null) {
//                FileDownloadRecord record = new FileDownloadRecord(
//                        upload.fileName,
//                        getFileSize(upload.fileName),
//                        "pending",
//                        null,
//                        System.currentTimeMillis(),
//                        0
//                );
//                dao.insert(record);
//                Timber.d("Added to download queue: %s", upload.fileName);
//                return true;
//            } else {
//                Timber.d("File already in download queue: %s (status: %s)",
//                        upload.fileName, existing.status);
//                return false;
//            }
//        } catch (Exception e) {
//            Timber.e(e, "Error adding upload to download queue: %s", upload.fileName);
//            return false;
//        }
//    }
//
//    private long getFileSize(String fileName) {
//        try {
//            File uploadQueueDir = new File(context.getFilesDir(), "upload_queue");
//            File uploadFile = new File(uploadQueueDir, fileName);
//            if (uploadFile.exists()) {
//                long size = uploadFile.length();
//                Timber.d("Got file size from upload queue: %s = %d bytes", fileName, size);
//                return size;
//            }
//
//            File vendorFile = new File("/data/vendor/udp_socket", fileName);
//            if (vendorFile.exists() && vendorFile.canRead()) {
//                long size = vendorFile.length();
//                Timber.d("Got file size from vendor directory: %s = %d bytes", fileName, size);
//                return size;
//            }
//
//            if (fileName.contains("boot_session")) {
//                Timber.d("Estimated large file size for: %s", fileName);
//                return 10 * 1024 * 1024;
//            } else {
//                Timber.d("Estimated small file size for: %s", fileName);
//                return 1024 * 1024;
//            }
//
//        } catch (Exception e) {
//            Timber.w("Could not determine file size for: %s, using 1MB estimate", fileName);
//            return 1024 * 1024;
//        }
//    }
//
//    public void downloadFile(FileDownloadRecord record, File targetFile) {
//        try {
//            long fileSize = record.fileSize;
//            Timber.i("STARTING DOWNLOAD: %s (%.2f MB)", record.fileName, fileSize / (1024.0 * 1024.0));
//            Timber.d("Download attempt #%d for this file", record.retryCount + 1);
//
//            String presignedUrl = getPresignedDownloadUrl(record.fileName);
//
//            boolean success = downloadFromS3(presignedUrl, targetFile, fileSize);
//
//            if (success) {
//                long actualSize = targetFile.length();
//                record.status = "completed";
//                record.failureReason = null;
//                record.fileSize = actualSize;
//                record.timestamp = System.currentTimeMillis();
//                dao.update(record);
//
//                Timber.i("DOWNLOAD SUCCESSFUL: %s (%.2f MB actual)", record.fileName, actualSize / (1024.0 * 1024.0));
//                Timber.d("File saved to: %s", targetFile.getAbsolutePath());
//            } else {
//                updateRecordFailure(record, "S3 download failed - server rejected the request");
//            }
//
//        } catch (Exception e) {
//            Timber.w("DOWNLOAD INTERRUPTED: %s", e.getMessage());
//
//            String userFriendlyReason;
//            if (e.getMessage().contains("Failed to connect")) {
//                userFriendlyReason = "Network connection issue - please check internet connectivity";
//                Timber.i("NETWORK ISSUE: Unable to reach AWS servers");
//            } else if (e.getMessage().contains("timeout")) {
//                userFriendlyReason = "Download timed out - file may be too large or connection too slow";
//                Timber.i("TIMEOUT: Download took too long to complete");
//            } else {
//                userFriendlyReason = "Unexpected error: " + e.getMessage();
//                Timber.i("UNKNOWN ERROR: %s", e.getMessage());
//            }
//
//            updateRecordFailure(record, userFriendlyReason);
//        }
//    }
//
//    // FIXED: Added "test/" prefix to fileName
//    private String getPresignedDownloadUrl(String fileName) throws Exception {
//        String s3Key = "test/" + fileName;  // Full S3 path
//
//        String jsonBody = "{\n" +
//                "  \"operation\": \"DL_FILE_EX\",\n" +
//                "  \"fileName\": \"" + s3Key + "\",\n" +   // SEND FULL PATH
//                "  \"fileType\": \"application/octet-stream\"\n" +
//                "}";
//
//        Timber.d("Download Request JSON: %s", jsonBody);
//
//        RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));
//        Request request = new Request.Builder()
//                .url(LAMBDA_URL)
//                .post(body)
//                .build();
//
//        try (Response response = httpClient.newCall(request).execute()) {
//            if (!response.isSuccessful() || response.body() == null) {
//                throw new RuntimeException("Lambda download URL request failed: HTTP " + response.code());
//            }
//
//            String responseBody = response.body().string();
//            Timber.d("Lambda Response: HTTP %d OK", response.code());
//            Timber.d("Raw Response: %s", responseBody.substring(0, Math.min(100, responseBody.length())) + "...");
//
//            try {
//                JSONObject jsonResponse = new JSONObject(responseBody);
//                String presignedUrl = jsonResponse.getString("url");
//                String method = jsonResponse.optString("method", "GET");
//
//                Timber.d("Extracted download URL (method: %s)", method);
//
//                if (!presignedUrl.startsWith("https://")) {
//                    throw new RuntimeException("Invalid download URL received");
//                }
//
//                return presignedUrl;
//
//            } catch (Exception e) {
//                Timber.e(e, "Failed to parse JSON response");
//                throw new RuntimeException("Failed to parse download URL from response: " + e.getMessage());
//            }
//        }
//    }
//
//    private boolean downloadFromS3(String presignedUrl, File targetFile, long expectedSize) {
//        try {
//            Request request = new Request.Builder()
//                    .url(presignedUrl)
//                    .get()
//                    .build();
//
//            Timber.d("Executing S3 download request...");
//
//            try (Response response = httpClient.newCall(request).execute()) {
//                if (!response.isSuccessful()) {
//                    String errorBody = response.body() != null ? response.body().string() : "No body";
//                    Timber.e("S3 download failed: HTTP %d - %s | Error: %s", response.code(), response.message(), errorBody);
//                    return false;
//                }
//
//                Timber.d("S3 Response: HTTP %d OK", response.code());
//
//                try (InputStream inputStream = response.body().byteStream();
//                     FileOutputStream outputStream = new FileOutputStream(targetFile)) {
//
//                    byte[] buffer = new byte[8192];
//                    long totalBytesRead = 0;
//                    int bytesRead;
//                    long lastProgressTime = System.currentTimeMillis();
//
//                    while ((bytesRead = inputStream.read(buffer)) != -1) {
//                        outputStream.write(buffer, 0, bytesRead);
//                        totalBytesRead += bytesRead;
//
//                        long currentTime = System.currentTimeMillis();
//                        if (currentTime - lastProgressTime > 5000) {
//                            if (expectedSize > 0) {
//                                double progressPercent = (totalBytesRead * 100.0) / expectedSize;
//                                Timber.d("Download progress: %.1f%% (%.2f MB / %.2f MB)",
//                                        progressPercent,
//                                        totalBytesRead / (1024.0 * 1024.0),
//                                        expectedSize / (1024.0 * 1024.0));
//                            } else {
//                                Timber.d("Downloaded: %.2f MB", totalBytesRead / (1024.0 * 1024.0));
//                            }
//                            lastProgressTime = currentTime;
//                        }
//                    }
//
//                    outputStream.flush();
//                    outputStream.getFD().sync();
//
//                    Timber.d("Downloaded %d bytes to: %s", totalBytesRead, targetFile.getName());
//                    return true;
//                }
//            }
//
//        } catch (Exception e) {
//            Timber.e(e, "S3 download exception");
//            return false;
//        }
//    }
//
//    private void updateRecordFailure(FileDownloadRecord record, String reason) {
//        record.status = "failed";
//        record.failureReason = reason;
//        record.timestamp = System.currentTimeMillis();
//        record.retryCount++;
//        dao.update(record);
//
//        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
//        String currentTime = sdf.format(new Date(record.timestamp));
//        String retryTime = sdf.format(new Date(System.currentTimeMillis() + (30 * 60 * 1000)));
//
//        Timber.w("Download FAILED for file: %s at %s", record.fileName, currentTime);
//        Timber.w("Failure reason: %s", reason);
//        Timber.i("RETRY SCHEDULED: Will attempt download again in 30 minutes");
//        Timber.i("Next retry time: %s", retryTime);
//    }
//}
//
//
//
//
//
//
//
//
//























//
//
//
//
//
//
//
//
//
//
//
//
//
//
//package com.example.database.service;
//
//import android.content.Context;
//import com.example.database.db.AppDatabase;
//import com.example.database.db.FileUploadDao;
//import com.example.database.db.FileUploadRecord;
//import com.example.database.db.FileDownloadDao;
//import com.example.database.db.FileDownloadRecord;
//import timber.log.Timber;
//
//import okhttp3.*;
//import org.json.JSONObject;
//
//import java.io.*;
//import java.text.SimpleDateFormat;
//import java.util.Date;
//import java.util.List;
//import java.util.Locale;
//import java.util.concurrent.TimeUnit;
//
//public class CloudDownloader {
//
//    private static final String LAMBDA_URL = "https://bpfsuu5xvj.execute-api.ap-south-1.amazonaws.com/default/s3uploadurlcreatorv1-dev-getPreSignedURLToPutToS3-dev";
//
//    private final Context context;
//    private final FileDownloadDao dao;
//    private final OkHttpClient httpClient;
//
//    public CloudDownloader(Context context, FileDownloadDao dao) {
//        this.context = context;
//        this.dao = dao;
//
//        this.httpClient = new OkHttpClient.Builder()
//                .connectTimeout(60, TimeUnit.SECONDS)
//                .readTimeout(300, TimeUnit.SECONDS)
//                .writeTimeout(60, TimeUnit.SECONDS)
//                .build();
//
//        Timber.d("Created download HTTP client (5 min read timeout)");
//    }
//
//    public void listS3Files() {
//        try {
//            Timber.d("Discovering uploaded files for download...");
//
//            AppDatabase db = AppDatabase.getInstance(context);
//            FileUploadDao uploadDao = db.fileUploadDao();
//
//            List<FileUploadRecord> successfulUploads = uploadDao.getSuccessfulRecords();
//
//            if (successfulUploads.isEmpty()) {
//                Timber.i("No successful uploads found - nothing to download");
//                return;
//            }
//
//            Timber.i("Found %d successfully uploaded files", successfulUploads.size());
//
//            int newDownloads = 0;
//            for (FileUploadRecord upload : successfulUploads) {
//                if (addUploadForDownload(upload)) {
//                    newDownloads++;
//                }
//            }
//
//            Timber.i("Added %d new files to download queue", newDownloads);
//
//        } catch (Exception e) {
//            Timber.e(e, "Error discovering uploaded files for download");
//        }
//    }
//
//    private boolean addUploadForDownload(FileUploadRecord upload) {
//        try {
//            FileDownloadRecord existing = dao.getRecordByFileName(upload.fileName);
//            if (existing == null) {
//                FileDownloadRecord record = new FileDownloadRecord(
//                        upload.fileName,
//                        getFileSize(upload.fileName),
//                        "pending",
//                        null,
//                        System.currentTimeMillis(),
//                        0
//                );
//                dao.insert(record);
//                Timber.d("Added to download queue: %s", upload.fileName);
//                return true;
//            } else {
//                Timber.d("File already in download queue: %s (status: %s)", upload.fileName, existing.status);
//                return false;
//            }
//        } catch (Exception e) {
//            Timber.e(e, "Error adding upload to download queue: %s", upload.fileName);
//            return false;
//        }
//    }
//
//    private long getFileSize(String fileName) {
//        try {
//            File uploadQueueDir = new File(context.getFilesDir(), "upload_queue");
//            File uploadFile = new File(uploadQueueDir, fileName);
//            if (uploadFile.exists()) {
//                long size = uploadFile.length();
//                Timber.d("Got file size from upload queue: %s = %d bytes", fileName, size);
//                return size;
//            }
//
//            File vendorFile = new File("/data/vendor/udp_socket", fileName);
//            if (vendorFile.exists() && vendorFile.canRead()) {
//                long size = vendorFile.length();
//                Timber.d("Got file size from vendor directory: %s = %d bytes", fileName, size);
//                return size;
//            }
//
//            if (fileName.contains("boot_session")) {
//                return 10 * 1024 * 1024; // 10MB estimate
//            } else {
//                return 1024 * 1024; // 1MB estimate
//            }
//
//        } catch (Exception e) {
//            Timber.w("Could not determine file size for: %s, using 1MB estimate", fileName);
//            return 1024 * 1024;
//        }
//    }
//
//    public void downloadFile(FileDownloadRecord record, File targetFile) {
//        try {
//            long fileSize = record.fileSize;
//            Timber.i("STARTING DOWNLOAD: %s (%.2f MB)", record.fileName, fileSize / (1024.0 * 1024.0));
//            Timber.d("Download attempt #%d", record.retryCount + 1);
//
//            String presignedUrl = getPresignedDownloadUrl(record.fileName);
//            boolean success = downloadFromS3(presignedUrl, targetFile, fileSize);
//
//            if (success) {
//                long actualSize = targetFile.length();
//                record.status = "completed";
//                record.failureReason = null;
//                record.fileSize = actualSize;
//                record.timestamp = System.currentTimeMillis();
//                dao.update(record);
//
//                // MAKE FILE WORLD-READABLE
//                targetFile.setReadable(true, false);
//                targetFile.setWritable(true, false);
//
//                Timber.i("DOWNLOAD SUCCESS: %s (%.2f MB)", record.fileName, actualSize / (1024.0 * 1024.0));
//                Timber.i("EXTERNAL SERVICE CAN ACCESS: %s", targetFile.getAbsolutePath());
//            } else {
//                updateRecordFailure(record, "S3 download failed");
//            }
//
//        } catch (Exception e) {
//            Timber.w("DOWNLOAD FAILED: %s", e.getMessage());
//            updateRecordFailure(record, e.getMessage());
//        }
//    }
//
//    private String getPresignedDownloadUrl(String fileName) throws Exception {
//        String s3Key = "test/" + fileName;
//
//        String jsonBody = "{\n" +
//                "  \"operation\": \"DL_FILE_EX\",\n" +
//                "  \"fileName\": \"" + s3Key + "\",\n" +
//                "  \"fileType\": \"application/octet-stream\"\n" +
//                "}";
//
//        Timber.d("Download Request JSON: %s", jsonBody);
//
//        RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));
//        Request request = new Request.Builder()
//                .url(LAMBDA_URL)
//                .post(body)
//                .build();
//
//        try (Response response = httpClient.newCall(request).execute()) {
//            if (!response.isSuccessful() || response.body() == null) {
//                throw new RuntimeException("Lambda failed: HTTP " + response.code());
//            }
//
//            String responseBody = response.body().string();
//            JSONObject json = new JSONObject(responseBody);
//            String url = json.getString("url");
//
//            if (!url.startsWith("https://")) {
//                throw new RuntimeException("Invalid URL");
//            }
//
//            return url;
//        }
//    }
//
//    private boolean downloadFromS3(String presignedUrl, File targetFile, long expectedSize) {
//        try {
//            Request request = new Request.Builder()
//                    .url(presignedUrl)
//                    .get()
//                    .build();
//
//            try (Response response = httpClient.newCall(request).execute()) {
//                if (!response.isSuccessful()) {
//                    String error = response.body() != null ? response.body().string() : "No body";
//                    Timber.e("S3 failed: HTTP %d | %s", response.code(), error);
//                    return false;
//                }
//
//                try (InputStream in = response.body().byteStream();
//                     FileOutputStream out = new FileOutputStream(targetFile)) {
//
//                    byte[] buffer = new byte[8192];
//                    long total = 0;
//                    int read;
//                    long lastLog = System.currentTimeMillis();
//
//                    while ((read = in.read(buffer)) != -1) {
//                        out.write(buffer, 0, read);
//                        total += read;
//
//                        if (System.currentTimeMillis() - lastLog > 5000) {
//                            if (expectedSize > 0) {
//                                double pct = (total * 100.0) / expectedSize;
//                                Timber.d("Progress: %.1f%% (%.2f/%.2f MB)", pct,
//                                        total / (1024.0 * 1024.0), expectedSize / (1024.0 * 1024.0));
//                            } else {
//                                Timber.d("Downloaded: %.2f MB", total / (1024.0 * 1024.0));
//                            }
//                            lastLog = System.currentTimeMillis();
//                        }
//                    }
//
//                    out.flush();
//                    out.getFD().sync();
//                    return true;
//                }
//            }
//        } catch (Exception e) {
//            Timber.e(e, "Download exception");
//            return false;
//        }
//    }
//
//    private void updateRecordFailure(FileDownloadRecord record, String reason) {
//        record.status = "failed";
//        record.failureReason = reason;
//        record.timestamp = System.currentTimeMillis();
//        record.retryCount++;
//        dao.update(record);
//
//        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
//        String now = sdf.format(new Date(record.timestamp));
//        String retry = sdf.format(new Date(System.currentTimeMillis() + 30 * 60 * 1000));
//
//        Timber.w("FAILED: %s at %s", record.fileName, now);
//        Timber.w("Reason: %s", reason);
//        Timber.i("RETRY IN 30 MIN: %s", retry);
//    }
//}














package com.example.database.service;

import android.content.Context;
import com.example.database.db.AppDatabase;
import com.example.database.db.FileUploadDao;
import com.example.database.db.FileUploadRecord;
import com.example.database.db.FileDownloadDao;
import com.example.database.db.FileDownloadRecord;
import timber.log.Timber;

import okhttp3.*;
import org.json.JSONObject;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class CloudDownloader {

    private static final String LAMBDA_URL = "https://bpfsuu5xvj.execute-api.ap-south-1.amazonaws.com/default/s3uploadurlcreatorv1-dev-getPreSignedURLToPutToS3-dev";

    private final Context context;
    private final FileDownloadDao dao;
    private final OkHttpClient httpClient;

    public CloudDownloader(Context context, FileDownloadDao dao) {
        this.context = context;
        this.dao = dao;

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();

        Timber.d("Created download HTTP client (5 min read timeout)");
    }

    public void listS3Files() {
        try {
            Timber.d("Discovering uploaded files for download...");

            AppDatabase db = AppDatabase.getInstance(context);
            FileUploadDao uploadDao = db.fileUploadDao();

            List<FileUploadRecord> successfulUploads = uploadDao.getSuccessfulRecords();

            if (successfulUploads.isEmpty()) {
                Timber.i("No successful uploads found - nothing to download");
                return;
            }

            Timber.i("Found %d successfully uploaded files", successfulUploads.size());

            int newDownloads = 0;
            for (FileUploadRecord upload : successfulUploads) {
                if (addUploadForDownload(upload)) {
                    newDownloads++;
                }
            }

            Timber.i("Added %d new files to download queue", newDownloads);

        } catch (Exception e) {
            Timber.e(e, "Error discovering uploaded files for download");
        }
    }

    private boolean addUploadForDownload(FileUploadRecord upload) {
        try {
            FileDownloadRecord existing = dao.getRecordByFileName(upload.fileName);
            if (existing == null) {
                FileDownloadRecord record = new FileDownloadRecord(
                        upload.fileName,
                        getFileSize(upload.fileName),
                        "pending",
                        null,
                        System.currentTimeMillis(),
                        0
                );
                dao.insert(record);
                Timber.d("Added to download queue: %s", upload.fileName);
                return true;
            } else {
                Timber.d("File already in download queue: %s (status: %s)", upload.fileName, existing.status);
                return false;
            }
        } catch (Exception e) {
            Timber.e(e, "Error adding upload to download queue: %s", upload.fileName);
            return false;
        }
    }

    private long getFileSize(String fileName) {
        try {
            File uploadQueueDir = new File(context.getFilesDir(), "upload_queue");
            File uploadFile = new File(uploadQueueDir, fileName);
            if (uploadFile.exists()) {
                long size = uploadFile.length();
                Timber.d("Got file size from upload queue: %s = %d bytes", fileName, size);
                return size;
            }

            File vendorFile = new File("/data/vendor/udp_socket", fileName);
            if (vendorFile.exists() && vendorFile.canRead()) {
                long size = vendorFile.length();
                Timber.d("Got file size from vendor directory: %s = %d bytes", fileName, size);
                return size;
            }

            if (fileName.contains("boot_session")) {
                return 10 * 1024 * 1024;
            } else {
                return 1024 * 1024;
            }

        } catch (Exception e) {
            Timber.w("Could not determine file size for: %s, using 1MB estimate", fileName);
            return 1024 * 1024;
        }
    }

    public void downloadFile(FileDownloadRecord record, File targetFile) {
        try {
            long fileSize = record.fileSize;
            Timber.i("STARTING DOWNLOAD: %s (%.2f MB)", record.fileName, fileSize / (1024.0 * 1024.0));
            Timber.d("Download attempt #%d", record.retryCount + 1);

            String presignedUrl = getPresignedDownloadUrl(record.fileName);
            boolean success = downloadFromS3(presignedUrl, targetFile, fileSize);

            if (success) {
                long actualSize = targetFile.length();
                record.status = "completed";
                record.failureReason = null;
                record.fileSize = actualSize;
                record.timestamp = System.currentTimeMillis();
                dao.update(record);

                targetFile.setReadable(true, false);
                targetFile.setWritable(true, false);

                Timber.i("DOWNLOAD SUCCESS: %s (%.2f MB)", record.fileName, actualSize / (1024.0 * 1024.0));
                Timber.i("EXTERNAL SERVICE CAN ACCESS: %s", targetFile.getAbsolutePath());
            } else {
                updateRecordFailure(record, "S3 download failed");
            }

        } catch (Exception e) {
            Timber.w("DOWNLOAD FAILED: %s", e.getMessage());
            updateRecordFailure(record, e.getMessage());
        }
    }

    // FIXED: Send only filename — Lambda adds "fota/"
    private String getPresignedDownloadUrl(String fileName) throws Exception {
        String cleanFileName = fileName
                .replace("fota/", "")
                .replace("test/", "");

        String jsonBody = "{\n" +
                "  \"operation\": \"DL_FILE_EX\",\n" +
                "  \"fileName\": \"" + cleanFileName + "\",\n" +
                "  \"fileType\": \"application/octet-stream\"\n" +
                "}";

        Timber.d("Download Request JSON: %s", jsonBody);

        RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(LAMBDA_URL)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new RuntimeException("Lambda failed: HTTP " + response.code());
            }

            String responseBody = response.body().string();
            JSONObject json = new JSONObject(responseBody);
            String url = json.getString("url");

            if (!url.startsWith("https://")) {
                throw new RuntimeException("Invalid URL");
            }

            return url;
        }
    }

    private boolean downloadFromS3(String presignedUrl, File targetFile, long expectedSize) {
        try {
            Request request = new Request.Builder()
                    .url(presignedUrl)
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String error = response.body() != null ? response.body().string() : "No body";
                    Timber.e("S3 failed: HTTP %d | %s", response.code(), error);
                    return false;
                }

                try (InputStream in = response.body().byteStream();
                     FileOutputStream out = new FileOutputStream(targetFile)) {

                    byte[] buffer = new byte[8192];
                    long total = 0;
                    int read;
                    long lastLog = System.currentTimeMillis();

                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        total += read;

                        if (System.currentTimeMillis() - lastLog > 5000) {
                            if (expectedSize > 0) {
                                double pct = (total * 100.0) / expectedSize;
                                Timber.d("Progress: %.1f%% (%.2f/%.2f MB)", pct,
                                        total / (1024.0 * 1024.0), expectedSize / (1024.0 * 1024.0));
                            } else {
                                Timber.d("Downloaded: %.2f MB", total / (1024.0 * 1024.0));
                            }
                            lastLog = System.currentTimeMillis();
                        }
                    }

                    out.flush();
                    out.getFD().sync();
                    return true;
                }
            }
        } catch (Exception e) {
            Timber.e(e, "Download exception");
            return false;
        }
    }

    private void updateRecordFailure(FileDownloadRecord record, String reason) {
        record.status = "failed";
        record.failureReason = reason;
        record.timestamp = System.currentTimeMillis();
        record.retryCount++;
        dao.update(record);

        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        String now = sdf.format(new Date(record.timestamp));
        String retry = sdf.format(new Date(System.currentTimeMillis() + 30 * 60 * 1000));

        Timber.w("FAILED: %s at %s", record.fileName, now);
        Timber.w("Reason: %s", reason);
        Timber.i("RETRY IN 30 MIN: %s", retry);
    }
}