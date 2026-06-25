// IFotaS3Callback.aidl
package android.app.fota.aidl;

// Declare any non-default types here with import statements

interface IFotaS3Callback {
    void onOtaAvailable(String payloadUrl);
}