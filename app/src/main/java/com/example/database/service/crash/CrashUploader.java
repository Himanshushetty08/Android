//package com.example.database.service.crash;
//
//import android.content.Context;
//import android.util.Log;
//
//import com.example.database.service.UploadManager;
//
//import java.io.File;
//import java.io.FileInputStream;
//import java.io.FileOutputStream;
//
//public class CrashUploader {
//
//    private static final String TAG = "CrashUploader";
//    private static final String CRASH_DIR = "/data/vendor/uv_crash_logs";
//
//    private final Context context;
//
//    public CrashUploader(Context context) {
//        this.context = context;
//    }
//
//    public void uploadCrash(String path, String content) {
//
//        Log.i(TAG, "uploadCrash() called");
//        Log.i(TAG, "Crash source path: " + path);
//
//        try {
//
//            File source = new File(path);
//
//            Log.d(TAG, "Source exists: " + source.exists());
//            Log.d(TAG, "Source readable: " + source.canRead());
//            Log.d(TAG, "Source size: " + source.length());
//
//            if (!source.exists()) {
//                Log.e(TAG, "Crash file missing: " + path);
//                return;
//            }
//
//            File crashDir = new File(CRASH_DIR);
//
//            Log.d(TAG, "Crash directory: " + CRASH_DIR);
//
//            if (!crashDir.exists()) {
//
//                Log.w(TAG, "Crash directory missing, creating it");
//
//                boolean created = crashDir.mkdirs();
//
//                // Fix directory permissions
//                crashDir.setReadable(true, false);
//                crashDir.setWritable(true, false);
//                crashDir.setExecutable(true, false);
//
//                Log.d(TAG, "Crash directory created: " + created);
//            }
//
//            File dest = new File(crashDir, source.getName());
//
//            Log.i(TAG, "Destination file: " + dest.getAbsolutePath());
//
//            copyFile(source, dest);
//
//            // IMPORTANT: Fix file permissions so UploadManager can read it
//            dest.setReadable(true, false);
//            dest.setWritable(true, false);
//            dest.setExecutable(false, false);
//
//            Log.i(TAG, "Crash copied successfully to: " + dest.getAbsolutePath());
//            Log.i(TAG, "Copied file size: " + dest.length());
//            Log.i(TAG, "Permission check -> readable=" + dest.canRead());
//
//            // Trigger upload pipeline
//            Log.i(TAG, "Triggering UploadManager.processFilesForcedEmergency()");
//
//            UploadManager.processFilesForcedEmergency(context);
//
//            Log.i(TAG, "UploadManager trigger completed");
//
//        } catch (Exception e) {
//
//            Log.e(TAG, "Crash upload failed", e);
//        }
//    }
//
//    private void copyFile(File source, File dest) throws Exception {
//
//        Log.d(TAG, "Starting file copy");
//
//        try (FileInputStream fis = new FileInputStream(source);
//             FileOutputStream fos = new FileOutputStream(dest)) {
//
//            byte[] buffer = new byte[8192];
//            int len;
//            long total = 0;
//
//            while ((len = fis.read(buffer)) > 0) {
//                fos.write(buffer, 0, len);
//                total += len;
//            }
//
//            fos.flush();
//
//            Log.d(TAG, "File copy completed, bytes copied: " + total);
//        }
//    }
//}