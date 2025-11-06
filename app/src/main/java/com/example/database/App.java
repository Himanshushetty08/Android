//package com.example.database;
//
//import android.app.Application;
//import android.content.Context;
//
//import androidx.work.ExistingPeriodicWorkPolicy;
//import androidx.work.PeriodicWorkRequest;
//import androidx.work.WorkManager;
//
//import java.util.concurrent.TimeUnit;
//
//import timber.log.Timber;
//
//public class App extends Application {
//
//    @Override
//    public void onCreate() {
//        super.onCreate();
//
//        if (isDebugBuild()) {
//            Timber.plant(new Timber.DebugTree());
//        }
//
//        // Schedule the upload job using WorkManager
//        scheduleUploadJob(this);
//    }
//
//    private boolean isDebugBuild() {
//        try {
//            Class<?> buildConfig = Class.forName(getPackageName() + ".BuildConfig");
//            return buildConfig.getField("DEBUG").getBoolean(null);
//        } catch (Exception e) {
//            // Default to true if unable to detect debug flag
//            return true;
//        }
//    }
//
//    private void scheduleUploadJob(Context context) {
//        PeriodicWorkRequest uploadWorkRequest = new PeriodicWorkRequest.Builder(
//                com.example.database.service.UploadJobService.class,
//                15,
//                TimeUnit.MINUTES)
//                .build();
//
//        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
//                "upload_job",
//                ExistingPeriodicWorkPolicy.REPLACE,
//                uploadWorkRequest);
//
//        Timber.d("Scheduled upload job with WorkManager.");
//    }
//}








//
//package com.example.database;
//
//import android.app.Application;
//import timber.log.Timber;
//import com.example.database.service.UploadJobService;
//
//public class App extends Application {
//
//    @Override
//    public void onCreate() {
//        super.onCreate();
//
//        // ✅ FIXED: Initialize Timber without BuildConfig check
//        Timber.plant(new Timber.DebugTree());
//
//        Timber.i("🚀 Database Upload Service Application Started");
//
//        // ✅ AUTO-START SERVICE (In case boot receiver doesn't work)
//        UploadJobService.scheduleUploadJob(this);
//
//        Timber.i("✅ Background service initialized from Application class");
//    }
//}











package com.example.database;

import android.app.Application;
import timber.log.Timber;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        // Initialize Timber for logging
        if (Timber.treeCount() == 0) {
            Timber.plant(new Timber.DebugTree());
        }

        Timber.i("🚀 App: Application initialized");
    }
}
