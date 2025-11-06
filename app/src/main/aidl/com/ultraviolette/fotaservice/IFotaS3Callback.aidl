// IFotaS3Callback.aidl
package com.ultraviolette.fotaservice;

// Declare any non-default types here with import statements

interface IFotaS3Callback {
    void onOtaAvailable(String payloadUrl);
}