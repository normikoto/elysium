package dev.biribiri.elysium.notification.internal;

import dev.biribiri.elysium.notification.Notification;
import dev.biribiri.elysium.notification.NotificationPolicy;
import dev.biribiri.elysium.notification.NotificationPolicy.Treatment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Decides, from the recipient's {@link NotificationPolicy}, whether a notification is added, filtered into a request, or
 * dropped, following Mastodon's {@code NotifyService}. Blocks and mutes are left to {@link NotificationSql#notHidden}.
 */
@Component
public class NotificationScreening {
    /**
     * The types Mastodon can filter. The rest come from what the recipient chose to hear about, such as a followed
     * account's {@code status}, or concern its own statuses, and {@code pending.quote} asks it to decide on a quote.
     */
    private static final Set<String> FILTERABLE = Set.of("mention", "reblog", "follow", "follow_request", "favourite",
            "quote", Notification.REACTION);
    /**
     * How far up a thread a private mention is looked for, as on Mastodon, so that a long or looping chain of replies
     * can't make one check expensive.
     */
    private static final int REPLY_DEPTH = 100;

    private final JdbcClient jdbc;

    NotificationScreening(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public NotificationPolicy policy(UUID account) {
        return jdbc.sql("SELECT * FROM notification_policy WHERE account_id = :account").param("account", account)
                .query((rs, row) -> new NotificationPolicy(treatment(rs.getString("for_not_following")),
                        treatment(rs.getString("for_not_followers")), treatment(rs.getString("for_new_accounts")),
                        treatment(rs.getString("for_private_mentions")), treatment(rs.getString("for_limited_accounts")),
                        treatment(rs.getString("for_bots"))))
                .optional().orElse(NotificationPolicy.DEFAULT);
    }

    public void save(UUID account, NotificationPolicy policy) {
        jdbc.sql("""
                        INSERT INTO notification_policy (account_id, for_not_following, for_not_followers, for_new_accounts,
                                                         for_private_mentions, for_limited_accounts, for_bots)
                        VALUES (:account, :notFollowing, :notFollowers, :newAccounts, :privateMentions, :limited, :bots)
                        ON CONFLICT (account_id) DO UPDATE SET for_not_following = EXCLUDED.for_not_following,
                            for_not_followers = EXCLUDED.for_not_followers, for_new_accounts = EXCLUDED.for_new_accounts,
                            for_private_mentions = EXCLUDED.for_private_mentions,
                            for_limited_accounts = EXCLUDED.for_limited_accounts, for_bots = EXCLUDED.for_bots
                        """)
                .param("account", account)
                .param("notFollowing", policy.forNotFollowing().value())
                .param("notFollowers", policy.forNotFollowers().value())
                .param("newAccounts", policy.forNewAccounts().value())
                .param("privateMentions", policy.forPrivateMentions().value())
                .param("limited", policy.forLimitedAccounts().value())
                .param("bots", policy.forBots().value())
                .update();
    }

    /**
     * Only local accounts have a policy, so a server's cleanup has nothing to remove here.
     */
    public void remove(UUID account) {
        jdbc.sql("DELETE FROM notification_policy WHERE account_id = :account").param("account", account).update();
    }

    /**
     * @param statusId the status the notification is about, or null
     */
    public Treatment decide(UUID recipient, UUID sender, String type, UUID statusId) {
        if (recipient.equals(sender) || !FILTERABLE.contains(type)) return Treatment.ACCEPT;
        var mention = type.equals("mention");
        var found = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM notification_permission
                                       WHERE recipient_id = :recipient AND sender_id = :sender) AS permitted,
                               EXISTS (SELECT 1 FROM account_credential ac
                                       WHERE ac.actor_id = :sender AND ac.role = 'ADMIN') AS admin,
                               NOT EXISTS (SELECT 1 FROM follow WHERE follower_id = :recipient AND followed_id = :sender
                                           AND state = 'accepted') AS not_following,
                               NOT EXISTS (SELECT 1 FROM follow WHERE follower_id = :sender AND followed_id = :recipient
                                           AND state = 'accepted' AND created_at <= now() - INTERVAL '3 days') AS not_follower,
                               COALESCE(la.created_at, p.published, a.created_at) > now() - INTERVAL '30 days' AS new_account,
                               COALESCE(p.bot, FALSE) AS bot
                        FROM actor a LEFT JOIN profile p ON p.actor_id = a.id LEFT JOIN local_account la ON la.actor_id = a.id
                        WHERE a.id = :sender
                        """)
                .param("recipient", recipient).param("sender", sender)
                .query((rs, row) -> new Sender(rs.getBoolean("permitted"), rs.getBoolean("admin"),
                        rs.getBoolean("not_following"), rs.getBoolean("not_follower"), rs.getBoolean("new_account"),
                        rs.getBoolean("bot")))
                .optional();
        if (found.isEmpty()) return Treatment.ACCEPT;
        var facts = found.get();
        if (facts.permitted() || (mention && facts.admin())) return Treatment.ACCEPT;
        var policy = policy(recipient);
        var treatment = Treatment.ACCEPT;
        if (facts.notFollowing()) treatment = treatment.stricter(policy.forNotFollowing());
        // A follow of a local account counts from when it was accepted, since approving a request resets its created_at.
        if (facts.notFollower()) treatment = treatment.stricter(policy.forNotFollowers());
        // Mastodon only applies the rest to accounts the recipient doesn't follow.
        if (facts.notFollowing()) {
            if (facts.newAccount()) treatment = treatment.stricter(policy.forNewAccounts());
            if (facts.bot()) treatment = treatment.stricter(policy.forBots());
            if (mention && treatment != Treatment.DROP && policy.forPrivateMentions() != Treatment.ACCEPT
                    && privateMentionNotInResponse(recipient, sender, statusId)) {
                treatment = treatment.stricter(policy.forPrivateMentions());
            }
        }
        return treatment;
    }

    /**
     * A direct status counts as a response, as on Mastodon, when it's below a direct status of the recipient's in its
     * thread that mentioned the sender, so that the sender can always answer what the recipient sent them.
     */
    private boolean privateMentionNotInResponse(UUID recipient, UUID sender, UUID statusId) {
        if (statusId == null) return false;
        return jdbc.sql("""
                        WITH RECURSIVE ancestor (id, in_reply_to_id, depth, found) AS (
                            SELECT p.id, p.in_reply_to_id, 1, %1$s
                            FROM status s JOIN status p ON p.id = s.in_reply_to_id WHERE s.id = :status
                            UNION ALL
                            SELECT p.id, p.in_reply_to_id, ancestor.depth + 1, %1$s
                            FROM ancestor JOIN status p ON p.id = ancestor.in_reply_to_id
                            WHERE NOT ancestor.found AND ancestor.depth < :depth
                        )
                        SELECT s.visibility = 'direct' AND NOT EXISTS (SELECT 1 FROM ancestor WHERE found)
                        FROM status s WHERE s.id = :status
                        """.formatted("""
                        (p.actor_id = :recipient AND p.visibility = 'direct' AND EXISTS (SELECT 1 FROM status_mention m
                         WHERE m.status_id = p.id AND m.actor_id = :sender AND NOT m.silent))"""))
                .param("recipient", recipient).param("sender", sender).param("status", statusId)
                .param("depth", REPLY_DEPTH)
                .query(Boolean.class).optional().orElse(false);
    }

    private static Treatment treatment(String value) {
        return Treatment.fromValue(value).orElseThrow();
    }

    private record Sender(boolean permitted, boolean admin, boolean notFollowing, boolean notFollower, boolean newAccount,
                          boolean bot) {
    }
}
