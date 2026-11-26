package dev.biribiri.elysium.api.web;

/**
 * Mastodon's page sizes for each kind of list: how many a page holds when a client doesn't say, and the most it can ask
 * for, which a larger request is clamped to. These are constants so that the API's documentation can give them.
 */
final class PageLimits {

    static final int STATUSES = 20;
    static final int MAX_STATUSES = 40;

    static final int ACCOUNTS = 40;
    static final int MAX_ACCOUNTS = 80;

    static final int NOTIFICATIONS = 40;
    static final int MAX_NOTIFICATIONS = 80;

    static final int NOTIFICATION_REQUESTS = 40;
    static final int MAX_NOTIFICATION_REQUESTS = 80;

    static final int CONVERSATIONS = 20;
    static final int MAX_CONVERSATIONS = 40;

    /**
     * For an account's own server blocks, and the admin API's domain blocks.
     */
    static final int DOMAIN_BLOCKS = 100;
    static final int MAX_DOMAIN_BLOCKS = 200;

    static final int FOLLOWED_TAGS = 100;
    static final int MAX_FOLLOWED_TAGS = 200;

    static final int REPORTS = 100;
    static final int MAX_REPORTS = 200;

    private PageLimits() {
    }
}
