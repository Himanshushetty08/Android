package com.example.database.utils;

public class DeviceSession {

    private static volatile String currentImei = null;

    public static void setImei(String imei) {
        currentImei = imei;
    }

    public static String getImei() {
        return currentImei;
    }
}
