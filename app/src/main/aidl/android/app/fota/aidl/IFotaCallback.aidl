// IFotaCallback.aidl
package android.app.fota.aidl;


// Declare any non-default types here with import statements

interface IFotaCallback {
    void onUpdateAvailable(String version, String payloadUrl);
    void onProgress(float percent);
    void onStatusChanged(int status);
}