// IFotaS3Events.aidl
package com.ultraviolette.fotaservice;

import com.ultraviolette.fotaservice.IFotaS3Callback;
// Declare any non-default types here with import statements

interface IFotaS3Events {

        void fotaDownloadRequest(String s3Key);
        void registerCallback(IFotaS3Callback callback);
        void unregisterCallback(IFotaS3Callback callback);

}