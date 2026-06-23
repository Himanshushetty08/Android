
package com.ultraviolette.s3service.service.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.telephony.*;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class NetworkSelector {
    private static final String TAG = "NetworkSelector";

    public static class Result {
        public final Network network;
        public final String type;      // "5G", "LTE", "WiFi"
        public final int rsrp;
        public final int rsrq;
        public final int sinr;
        public final int rssi;

        Result(Network network, String type, int rsrp, int rsrq, int sinr, int rssi) {
            this.network = network;
            this.type = type;
            this.rsrp = rsrp;
            this.rsrq = rsrq;
            this.sinr = sinr;
            this.rssi = rssi;
        }

        @NonNull
        @Override
        public String toString() {
            return type + " | RSRP:" + rsrp + " | SINR:" + sinr + " | RSSI:" + rssi;
        }
    }

    @Nullable
    public static Result getBestNetwork(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;

        Result best = null;
        double bestScore = -999;

        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null) continue;

            Result candidate = new Result(network, "UNKNOWN", -140, -20, -20, -100);

            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                candidate = evaluateWifi(context, network);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                candidate = evaluateCellular(context, network);
            }

            double score = calculateScore(candidate);
            Log.d(TAG, "Candidate → " + candidate + " | Score: " + String.format("%.1f", score));

            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        if (best != null) {
            Log.w(TAG, "BEST NETWORK SELECTED → " + best);
        }
        return best;
    }

    private static Result evaluateWifi(Context context, Network network) {
        WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = wifi.getConnectionInfo();
        int rssi = (info != null && info.getNetworkId() != -1) ? info.getRssi() : -100;
        return new Result(network, "WiFi", rssi, 0, 0, rssi);
    }

    private static Result evaluateCellular(Context context, Network network) {
        TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        Result r = new Result(network, "LTE", -140, -20, -20, 0);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                SignalStrength ss = tm.getSignalStrength();
                if (ss != null) {
                    for (CellSignalStrength css : ss.getCellSignalStrengths()) {
                        if (css instanceof CellSignalStrengthNr) {
                            CellSignalStrengthNr nr = (CellSignalStrengthNr) css;
                            return new Result(network, "5G",
                                    nr.getSsRsrp(), nr.getSsRsrq(), nr.getSsSinr(), 0);
                        }
                        if (css instanceof CellSignalStrengthLte) {
                            CellSignalStrengthLte lte = (CellSignalStrengthLte) css;
                            r = new Result(network, "LTE", lte.getRsrp(), lte.getRsrq(), 0, 0);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Signal read failed", e);
        }
        return r;
    }

    private static double calculateScore(Result r) {
        double score = 0;
        if ("5G".equals(r.type)) {
            score += 1000;
            score += r.sinr * 15;        // SINR is king on 5G
            score += (r.rsrp + 90) * 2;
        } else if ("LTE".equals(r.type)) {
            score += 600;
            score += (r.rsrp + 95) * 3;
        } else if ("WiFi".equals(r.type)) {
            score += 400;
            score += (r.rssi + 70) * 2;
        }
        return score;
    }
}

//todo: while uploading if wifi disconnects te whole network must reset