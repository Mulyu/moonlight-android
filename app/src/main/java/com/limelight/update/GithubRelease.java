package com.limelight.update;

import java.util.List;

/** The subset of GitHub's release API response this package needs. */
class GithubRelease {
    String tag_name;
    String name;
    String body;
    String html_url;
    List<Asset> assets;

    static class Asset {
        String name;
        String browser_download_url;
    }
}
