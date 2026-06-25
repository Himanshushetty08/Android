// IFotaMqttEvents.aidl
package android.app.fota.aidl;


// Declare any non-default types here with import statements

interface IFotaMqttEvents {

    // MqttService uses this to notify FotaService of new OTA
    void fotaUpdateAvailable(String payload);
}