// IFotaS3Events.aidl
package android.app.fota.aidl;


import android.app.fota.aidl.IFotaS3Callback;
// Declare any non-default types here with import statements

interface IFotaS3Events {

        void fotaDownloadRequest(String s3Key);
        void registerCallback(IFotaS3Callback callback);
        void unregisterCallback(IFotaS3Callback callback);

}