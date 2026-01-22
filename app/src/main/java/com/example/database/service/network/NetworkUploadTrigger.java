package com.example.database.service.network;

import android.content.Context;
import android.net.*;
import android.util.Log;

import com.example.database.service.UploadManager;

public class NetworkUploadTrigger {

    private static boolean registered = false;

    public static void register(Context context) {
        if (registered) return;
        registered = true;

        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();

        cm.registerNetworkCallback(request, new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    Log.i("NetworkUploadTrigger", "WiFi connected → triggering upload immediately");
                    UploadManager.processFiles(context);
                }
            }
        });
    }
}
