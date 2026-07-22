package com.ultraviolette.s3service;

/**
 * Client-side contract for the external S3Service APK (package com.ultraviolette.s3service).
 *
 * The cluster asks the cloud for its vehicle documents (MQTT cmd 11 — see CommandManager); the
 * cloud replies with short-lived presigned S3 URLs. Downloading those URLs — networking, storage,
 * retries, credentials — is owned entirely by the S3Service APK. The cluster only hands off
 * (documentType, presignedUrl) pairs; no download logic crosses this boundary.
 *
 * For cmd 17 (F77 image), the ClusterDataBus calls downloadDocument("f77_image", url).
 *
 * The sentinel value "d" for presignedUrl means the document was not found in S3 — the service
 * must delete any locally stored file for that documentType.
 *
 * PLACEHOLDER CONTRACT — the package, this interface's fully-qualified name (the AIDL binder
 * descriptor), and the bind action string are a shared contract with the ClusterDataBus and MUST
 * match what that APK ships, byte-for-byte, or the bind/transaction fails.
 */
oneway interface IS3Service {
    /**
     * Hand off a presigned URL for one document so S3Service can download and store it, or
     * delete the local copy when the cloud signals the document is absent.
     *
     * @param documentType  Cloud document key: "license", "registration", "insurance",
     *                      "f77_image", etc.
     * @param presignedUrl  Short-lived S3 presigned URL to download from, or the sentinel
     *                      value "d" meaning the document does not exist — delete local copy.
     */
    void downloadDocument(String payload);
}