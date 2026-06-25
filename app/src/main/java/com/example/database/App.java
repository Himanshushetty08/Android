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
//package com.example.database;
//
//import android.app.Application;
//import timber.log.Timber;
//
//public class App extends Application {
//    @Override
//    public void onCreate() {
//        super.onCreate();
//
//        // Initialize Timber for logging
//        if (Timber.treeCount() == 0) {
//            Timber.plant(new Timber.DebugTree());
//        }
//
//        Timber.i("🚀 App: Application initialized");
//    }
//}



package com.example.database;

import android.app.Application;
import android.content.Context;
import android.os.UserManager;

import timber.log.Timber;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        // MUST BE FIRST — before anything touches Room/DB
        UserManager um = (UserManager) getSystemService(Context.USER_SERVICE);
        if (um == null || !um.isSystemUser()) {
            // Kill User 10 process immediately — no DB, no service, nothing
            android.os.Process.killProcess(android.os.Process.myPid());
            return;
        }

        // Only User 0 reaches here
        if (Timber.treeCount() == 0) {
            Timber.plant(new Timber.DebugTree());
        }
        Timber.i("App: Application initialized (User 0)");
    }
}