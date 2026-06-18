// IFotaUmsCallback.aidl
// Package: com.ultraviolette.aidl
//
// Place in: aidl-lib/src/main/aidl/com/ultraviolette/aidl/
//
// WHO IMPLEMENTS THIS:
//   UmsService implements IFotaUmsCallback.Stub and registers itself
//   with DatabaseBackgroundService via IFotaUmsEvents.registerUmsCallback()
//
// WHO CALLS THIS:
//   DatabaseBackgroundService calls these methods on UmsService
//   to report download state changes and progress.
//
// STATE VALUES (matches FotaState.State enum exactly):
//   "DOWNLOADING"       → download started or resumed
//   "DOWNLOAD_PAUSED"   → paused (wifi lost, vehicle moving etc.)
//   "DOWNLOADED"        → complete, file ready at filePath
//   "ERROR"             → failed with reason

package com.ultraviolette.aidl;

interface IFotaUmsCallback {

    /**
     * Called by DatabaseBackgroundService when download state changes.
     *
     * UmsService drives state machine transitions from this:
     *   "DOWNLOADING"     → transitionTo(DOWNLOADING)
     *   "DOWNLOAD_PAUSED" → transitionTo(DOWNLOAD_PAUSED)
     *   "DOWNLOADED"      → transitionTo(DOWNLOADED)
     *   "ERROR"           → transitionTo(ERROR)
     *
     * @param state       one of: DOWNLOADING, DOWNLOAD_PAUSED, DOWNLOADED, ERROR
     * @param filePath    absolute path to downloaded file
     *                    e.g. /data/vendor/uv_fota/fota/fota.tar
     *                    only meaningful when state = DOWNLOADED
     *                    pass "" for other states
     * @param reason      reason string for ERROR state
     *                    e.g. "NETWORK_ERROR", "HTTP_403", "FILE_NOT_FOUND"
     *                    pass "" for non-error states
     * @param campaignId  which campaign this download belongs to
     *                    matches FotaState.campaignId
     */
    void onStateChanged(
        String state,
        String filePath,
        String reason,
        String campaignId
    );

    /**
     * Called periodically during download with progress.
     *
     * UmsService uses this to:
     *   1. Update FotaState.lastVerifiedOffset → persisted to state.pb
     *      so downloads resume from the right byte after a reboot
     *   2. Forward progress to VehicleMonitorService via IUmsCallback
     *
     * Called from inside the download loop in CloudDownloader —
     * currently where the "ProgggsssLeeLLineww" log line is.
     *
     * @param campaignId       which campaign is downloading
     * @param progressPercent  0–100, pass -1 if total size unknown
     * @param bytesDownloaded  bytes confirmed written to disk
     * @param totalBytes       total expected file size, 0 if unknown
     */
    void onProgress(
        String campaignId,
        int progressPercent,
        long bytesDownloaded,
        long totalBytes
    );
}