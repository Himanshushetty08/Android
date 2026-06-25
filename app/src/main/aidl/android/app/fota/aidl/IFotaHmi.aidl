// IFotaHmi.aidl
package android.app.fota.aidl;


import  android.app.fota.aidl.IFotaCallback;

// Declare any non-default types here with import statements

// Only for HMI
interface IFotaHmi {
    void installUpdate();
    void cancelUpdate();
    int getStatus();
    void registerCallback(IFotaCallback cb);
    void unregisterCallback(IFotaCallback cb);
}