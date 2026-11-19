package dev.biribiri.elysium.web;

/**
 * The lists a profile page can show, at the same paths as Mastodon's.
 *
 * @param path what follows the profile's own path
 */
enum ProfileTab {
    POSTS("", true, false),
    WITH_REPLIES("/with_replies", false, false),
    MEDIA("/media", false, true);

    final String path;
    final boolean excludeReplies;
    final boolean onlyMedia;

    ProfileTab(String path, boolean excludeReplies, boolean onlyMedia) {
        this.path = path;
        this.excludeReplies = excludeReplies;
        this.onlyMedia = onlyMedia;
    }

    public String getPath() {
        return path;
    }
}
