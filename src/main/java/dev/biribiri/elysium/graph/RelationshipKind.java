package dev.biribiri.elysium.graph;

/**
 * What a CSV file of accounts lists, named as Mastodon names its exports, so that files move between the two.
 */
public enum RelationshipKind {

    FOLLOWS("follows", "following_accounts.csv"),
    BLOCKS("blocks", "blocked_accounts.csv"),
    MUTES("mutes", "muted_accounts.csv");

    private final String key;
    private final String fileName;

    RelationshipKind(String key, String fileName) {
        this.key = key;
        this.fileName = fileName;
    }

    /**
     * How forms and the database name it.
     */
    public String key() {
        return key;
    }

    /**
     * The name an export is downloaded as.
     */
    public String fileName() {
        return fileName;
    }
}
