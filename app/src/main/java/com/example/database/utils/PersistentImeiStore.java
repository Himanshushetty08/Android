//package com.example.database.utils;
//
//import android.content.Context;
//import android.content.SharedPreferences;
//import android.util.Log;
//
//public class PersistentImeiStore {
//
//    private static final String PREF_NAME = "persistent_imei_store";
//    private static final String KEY_IMEI = "imei";
//
//    public static void save(Context context, String imei) {
//
//        Context dp = context.createDeviceProtectedStorageContext();
//
//        dp.getSharedPreferences("persistent_imei_store", Context.MODE_PRIVATE)
//                .edit()
//                .putString("imei", imei)
//                .commit();
//
//        Log.i("PersistentImeiStore", "IMEI SAVED = " + imei);
//    }
//    public static String load(Context context) {
//        Context dp = context.createDeviceProtectedStorageContext(); // wrap internally
//        return dp.getSharedPreferences("persistent_imei_store", Context.MODE_PRIVATE)
//                .getString("imei", null);
//    }
//
//    public static void debugDump(Context context) {
//        Log.w("IMEI_DEBUG", "=== PersistentImeiStore DEBUG DUMP ===");
//
//        // Check 1: Regular context
//        try {
//            String v1 = context.getSharedPreferences("persistent_imei_store", Context.MODE_PRIVATE)
//                    .getString("imei", "NOT_FOUND");
//            Log.w("IMEI_DEBUG", "Regular context → " + v1);
//        } catch (Exception e) {
//            Log.e("IMEI_DEBUG", "Regular context → EXCEPTION: " + e.getMessage());
//        }
//
//        // Check 2: Device protected context
//        try {
//            Context dp = context.createDeviceProtectedStorageContext();
//            String v2 = dp.getSharedPreferences("persistent_imei_store", Context.MODE_PRIVATE)
//                    .getString("imei", "NOT_FOUND");
//            Log.w("IMEI_DEBUG", "DeviceProtected context → " + v2);
//        } catch (Exception e) {
//            Log.e("IMEI_DEBUG", "DeviceProtected context → EXCEPTION: " + e.getMessage());
//        }
//
//        // Check 3: App context
//        try {
//            String v3 = context.getApplicationContext()
//                    .getSharedPreferences("persistent_imei_store", Context.MODE_PRIVATE)
//                    .getString("imei", "NOT_FOUND");
//            Log.w("IMEI_DEBUG", "AppContext → " + v3);
//        } catch (Exception e) {
//            Log.e("IMEI_DEBUG", "AppContext → EXCEPTION: " + e.getMessage());
//        }
//
//        // Check 4: App context + device protected
//        try {
//            Context dp = context.getApplicationContext().createDeviceProtectedStorageContext();
//            String v4 = dp.getSharedPreferences("persistent_imei_store", Context.MODE_PRIVATE)
//                    .getString("imei", "NOT_FOUND");
//            Log.w("IMEI_DEBUG", "AppContext+DeviceProtected → " + v4);
//        } catch (Exception e) {
//            Log.e("IMEI_DEBUG", "AppContext+DeviceProtected → EXCEPTION: " + e.getMessage());
//        }
//
//        Log.w("IMEI_DEBUG", "======================================");
//    }
//}


















package com.example.database.utils;

import android.util.Log;

import java.lang.reflect.Method;

public class PersistentImeiStore {

    private static final String TAG = "PersistentImeiStore";
    private static final String PROP_KEY = "persist.device.imei";

    public static void save(String imei) {
        try {
            Class<?> SystemProperties = Class.forName("android.os.SystemProperties");
            Method set = SystemProperties.getMethod("set", String.class, String.class);
            set.invoke(null, PROP_KEY, imei);
            Log.i(TAG, "IMEI SAVED to system property = " + imei);
        } catch (Exception e) {
            Log.e(TAG, "Failed to save IMEI to system property", e);
        }
    }

    public static String load() {
        try {
            Class<?> SystemProperties = Class.forName("android.os.SystemProperties");
            Method get = SystemProperties.getMethod("get", String.class, String.class);
            String imei = (String) get.invoke(null, PROP_KEY, null);
            Log.i(TAG, "IMEI LOADED from system property = " + imei);
            return imei;
        } catch (Exception e) {
            Log.e(TAG, "Failed to load IMEI from system property", e);
            return null;
        }
    }
}