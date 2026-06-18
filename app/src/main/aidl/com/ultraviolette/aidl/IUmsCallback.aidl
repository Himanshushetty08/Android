// IUmsCallback.aidl
// Package: com.ultraviolette.aidl
//
// Place in: aidl-lib/src/main/aidl/com/ultraviolette/aidl/
//
// WHO IMPLEMENTS THIS:
//   VehicleMonitorService implements IUmsCallback.Stub and registers
//   it with UmsService via IUmsService.registerCallback()
//
// WHO CALLS THIS:
//   UmsService calls these methods on VehicleMonitorService
//   whenever state transitions happen.

package com.ultraviolette.aidl;

interface IUmsCallback {

    /**
     * Called after EVERY state transition in UmsService.
     * Fired AFTER state.pb has been persisted to disk —
     * new state is safely saved when this arrives.
     *
     * @param fromState   state we just left  e.g. "DOWNLOADING"
     * @param toState     state we entered    e.g. "DOWNLOADED"
     * @param trigger     what caused it      e.g. "download_complete"
     * @param campaignId  which campaign this is for
     */
    void onStateChanged(
        String fromState,
        String toState,
        String trigger,
        String campaignId
    );

    /**
     * Called when update completes successfully.
     *
     * @param campaignId      which campaign succeeded
     * @param packageVersion  the version that was installed
     */
    void onUpdateSuccess(String campaignId, String packageVersion);

    /**
     * Called when UMS encounters an error after exhausting retries.
     *
     * @param campaignId   which campaign failed
     * @param errorCode    e.g. "HASH_MISMATCH", "INSTALL_FAILED"
     * @param errorMessage human-readable description
     * @param module       which module threw the error
     */
    void onError(
        String campaignId,
        String errorCode,
        String errorMessage,
        String module
    );

    /**
     * Called when UMS triggers a rollback to previous firmware.
     *
     * @param campaignId  which campaign triggered rollback
     * @param reason      why rollback was triggered
     */
    void onRollback(String campaignId, String reason);

    /**
     * Called periodically during download with progress.
     * VehicleMonitorService can use this to decide whether conditions
     * are still suitable for continuing the download.
     *
     * @param campaignId       which campaign is downloading
     * @param progressPercent  0–100
     * @param bytesDownloaded  bytes confirmed downloaded so far
     */
    void onDownloadProgress(
        String campaignId,
        int progressPercent,
        long bytesDownloaded
    );
}