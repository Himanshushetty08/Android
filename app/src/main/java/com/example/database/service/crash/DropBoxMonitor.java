//package com.example.database.service.crash;
//
//import android.content.Context;
//import android.os.DropBoxManager;
//import android.util.Log;
//
//import com.example.database.service.UploadManager;
//
//import java.io.File;
//import java.io.FileOutputStream;
//
//public class DropBoxMonitor {
//
//    private static final String TAG = "DropBoxMonitor";
//    private static final String CRASH_DIR = "/data/vendor/uv_crash_logs";
//
//    private final Context context;
//    private final DropBoxManager dropBoxManager;
//
//    private long lastTime = 0;
//
//    public DropBoxMonitor(Context context) {
//        this.context = context;
//        this.dropBoxManager =
//                (DropBoxManager) context.getSystemService(Context.DROPBOX_SERVICE);
//    }
//
//    public void checkEntries() {
//
//        try {
//
//            String[] tags = {
//                    "SYSTEM_TOMBSTONE",
//                    "system_server_crash",
//                    "system_server_anr",
//                    "system_app_crash",
//                    "system_app_anr"
//            };
//
//            for (String tag : tags) {
//
//                DropBoxManager.Entry entry =
//                        dropBoxManager.getNextEntry(tag, lastTime);
//
//                while (entry != null) {
//
//                    long time = entry.getTimeMillis();
//
//                    String fileName = tag + "_" + time + ".txt";
//
//                    File dest = new File(CRASH_DIR, fileName);
//
//                    saveEntry(entry, dest);
//
//                    lastTime = time;
//
//                    entry.close();
//
//                    entry = dropBoxManager.getNextEntry(tag, lastTime);
//                }
//            }
//
//        } catch (Exception e) {
//
//            Log.e(TAG, "DropBox read error", e);
//
//        }
//    }
//
//    private void saveEntry(DropBoxManager.Entry entry, File dest) throws Exception {
//
//        if (!dest.getParentFile().exists()) {
//            dest.getParentFile().mkdirs();
//        }
//
//        try (FileOutputStream fos = new FileOutputStream(dest)) {
//
//            byte[] data = entry.getText(1024 * 1024).getBytes();
//
//            fos.write(data);
//
//        }
//
//        Log.i(TAG, "DropBox crash saved → " + dest.getAbsolutePath());
//
//        UploadManager.processFiles(context);
//    }
//}