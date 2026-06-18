// IUmsService.aidl
// Package: com.ultraviolette.aidl
//
// Place in: aidl-lib/src/main/aidl/com/ultraviolette/aidl/
//
// WHO EXPOSES THIS:
//   UmsService exposes IUmsService.Stub via onBind()
//   when intent action = "com.ultraviolette.fotaservice.BIND_UMS"
//
// WHO CALLS THIS:
//   VehicleMonitorService binds to UmsService and calls:
//     - Query methods to check current state before sending commands
//     - Command methods to pause/resume/poll
//     - Register callback to receive state change notifications

package com.ultraviolette.aidl;

import com.ultraviolette.aidl.IUmsCallback;

interface IUmsService {

    // -------------------------------------------------------------------------
    // Query Methods — read only, safe on Binder thread
    // These are what make AIDL better than Intent —
    // you can ask questions and get answers back.
    // -------------------------------------------------------------------------

    /**
     * Returns the current UMS state as a string.
     * e.g. "IDLE", "DOWNLOADING", "INSTALLING", "SUCCESS"
     *
     * VehicleMonitorService calls this before deciding to pause/resume —
     * no point sending pause if UMS is not downloading.
     */
    String getCurrentState();

    /**
     * Returns the active campaign ID, or null if no campaign is active.
     */
    String getActiveCampaignId();

    /**
     * Returns current retry count for the active operation.
     */
    int getRetryCount();

    /**
     * Returns true if an update is currently in progress.
     * i.e. not IDLE, SUCCESS, ERROR, or ROLLBACK.
     */
    boolean isUpdateInProgress();

    // -------------------------------------------------------------------------
    // Command Methods — sent from VehicleMonitorService to UmsService
    // -------------------------------------------------------------------------

    /**
     * Pause an in-progress download.
     * Only has effect if UMS is in DOWNLOADING state.
     *
     * @param reason  e.g. "vehicle_moving", "low_soc", "thermal_throttle"
     */
    void pauseDownload(String reason);

    /**
     * Resume a paused download.
     * Only has effect if UMS is in DOWNLOAD_PAUSED state.
     */
    void resumeDownload();

    /**
     * Immediately trigger a /fota/check outside the 48hr schedule.
     * Used when vehicle connects to WiFi or network improves.
     */
    void forcePoll();

    /**
     * Dump current state as human-readable JSON for diagnostics.
     * Output: /data/uv_fota/<campaignId>/state_dump.json
     */
    void dumpState();

    // -------------------------------------------------------------------------
    // Callback Registration
    // -------------------------------------------------------------------------

    /**
     * Register to receive state change notifications from UmsService.
     * Call after binding — UmsService will call IUmsCallback methods
     * on every state transition.
     */
    void registerCallback(IUmsCallback callback);

    /**
     * Unregister a previously registered callback.
     * Always call on shutdown to prevent leaks.
     */
    void unregisterCallback(IUmsCallback callback);
}