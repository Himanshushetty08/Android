// IFotaUmsEvents.aidl
// Package: com.ultraviolette.aidl
//
// Place in: aidl-lib/src/main/aidl/com/ultraviolette/aidl/
//
// WHO EXPOSES THIS:
//   UmsService exposes IFotaUmsEvents.Stub via onBind()
//   when intent action = "com.ultraviolette.fotaservice.BIND_DM"
//
// WHO CALLS THIS:
//   DatabaseBackgroundService binds to UmsService using this action
//   and calls registerUmsCallback() to register as the download reporter.
//
// FLOW:
//   1. DatabaseBackgroundService binds to UmsService (BIND_DM action)
//   2. Gets IFotaUmsEvents proxy from onServiceConnected()
//   3. Calls registerUmsCallback(this) — passes its IFotaUmsCallback impl
//   4. Now DM calls umsCallback.onStateChanged() at every state change
//   5. On shutdown DM calls unregisterUmsCallback(this)

package com.ultraviolette.aidl;

import com.ultraviolette.aidl.IFotaUmsCallback;

interface IFotaUmsEvents {

    /**
     * DatabaseBackgroundService calls this after binding to UmsService.
     * Registers itself as the download state reporter.
     *
     * After this call, DM calls IFotaUmsCallback methods proactively
     * whenever download state changes — no polling needed.
     *
     * @param callback  IFotaUmsCallback implementation in DM
     */
    void registerUmsCallback(IFotaUmsCallback callback);

    /**
     * DatabaseBackgroundService calls this on shutdown.
     * Always call to prevent memory leaks.
     *
     * @param callback  the same callback passed to registerUmsCallback()
     */
    void unregisterUmsCallback(IFotaUmsCallback callback);

    /**
     * Returns current UMS state as string.
     * DM calls this to check if UMS is ready before starting a download.
     * e.g. if UMS is INSTALLING, DM should not start a new download.
     *
     * @return state string e.g. "IDLE", "DOWNLOADING", "INSTALLING"
     */
    String getCurrentState();
}