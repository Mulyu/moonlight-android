package com.limelight.update;

/** Result of a successful update check: a release newer than the running build. */
class UpdateInfo {
    int buildNumber;
    String releaseName;
    String releaseNotes;
    String releasePageUrl;
    /** APK asset matching this device's ABI, or null if the release has none. */
    String downloadUrl;
}
