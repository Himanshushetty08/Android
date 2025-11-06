// IFotaHmi.aidl
package com.ultraviolette.fotaservice;

import com.ultraviolette.fotaservice.IFotaCallback;

// Declare any non-default types here with import statements

// Only for HMI
interface IFotaHmi {
    void installUpdate();
    void cancelUpdate();
    int getStatus();
    void registerCallback(IFotaCallback cb);
    void unregisterCallback(IFotaCallback cb);
}