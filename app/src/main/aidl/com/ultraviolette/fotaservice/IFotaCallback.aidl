// IFotaCallback.aidl
package com.ultraviolette.fotaservice;

// Declare any non-default types here with import statements

interface IFotaCallback {
    void onUpdateAvailable(String version, String payloadUrl);
    void onProgress(float percent);
    void onStatusChanged(int status);
}