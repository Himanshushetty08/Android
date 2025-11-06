// IFotaMqttEvents.aidl
package com.ultraviolette.fotaservice;

// Declare any non-default types here with import statements

interface IFotaMqttEvents {

    // MqttService uses this to notify FotaService of new OTA
    void fotaUpdateAvailable(String payload);
}