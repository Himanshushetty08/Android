package com.example.database.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

public class NetworkUtils {
    public static boolean isWifiConnected(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            NetworkInfo ni = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            return ni != null && ni.isConnected();
        }
        return false;
    }
}
