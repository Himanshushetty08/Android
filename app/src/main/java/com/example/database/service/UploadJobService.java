//package com.example.database.service;
//
//import android.content.Context;
//import androidx.annotation.NonNull;
//import androidx.work.Worker;
//import androidx.work.WorkerParameters;
//import androidx.work.PeriodicWorkRequest;
//import androidx.work.WorkManager;
//import androidx.work.ExistingPeriodicWorkPolicy;
//import timber.log.Timber;
//
//import java.util.concurrent.TimeUnit;
//
//public class UploadJobService extends Worker {
//
//    public UploadJobService(@NonNull Context context, @NonNull WorkerParameters params) {
//        super(context, params);
//    }
//
//    @NonNull
//    @Override
//    public Result doWork() {
//        Timber.i("UploadJobService: Scheduled upload fired by WorkManager");
//        UploadManager.processFiles(getApplicationContext());
//        return Result.success();
//    }
//
//    public static void scheduleUploadJob(Context context) {
//        PeriodicWorkRequest uploadWorkRequest = new PeriodicWorkRequest.Builder(
//                UploadJobService.class,
//                15,
//                TimeUnit.MINUTES)
//                .build();
//
//        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
//                "upload_job",
//                ExistingPeriodicWorkPolicy.REPLACE,
//                uploadWorkRequest);
//    }
//}






































package com.example.database.service;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.ExistingPeriodicWorkPolicy;
import timber.log.Timber;

import java.util.concurrent.TimeUnit;

public class UploadJobService extends Worker {

    public UploadJobService(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Timber.i("UploadJobService: Scheduled job fired by WorkManager");

        // ✅ EXISTING: Process uploads
        UploadManager.processFiles(getApplicationContext());

        // ✅ ADD: Process downloads
        CloudDownloadManager.processDownloads(getApplicationContext());

        return Result.success();
    }

    public static void scheduleUploadJob(Context context) {
        PeriodicWorkRequest uploadWorkRequest = new PeriodicWorkRequest.Builder(
                UploadJobService.class,
                15,
                TimeUnit.MINUTES)
                .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "upload_job",  // ✅ KEEP: Same job name for simplicity
                ExistingPeriodicWorkPolicy.REPLACE,
                uploadWorkRequest);

        Timber.i("📅 Scheduled combined upload/download job - runs every 15 minutes");
    }
}
