package dev.biribiri.elysium.timeline.internal;

import dev.biribiri.elysium.actor.IdPage;
import dev.biribiri.elysium.content.StatusOrigin;
import dev.biribiri.elysium.content.TagFilter;
import dev.biribiri.elysium.graph.AccountList;
import dev.biribiri.elysium.graph.Following;
import dev.biribiri.elysium.graph.HiddenAccounts;
import dev.biribiri.elysium.graph.ListMembers;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static dev.biribiri.elysium.content.StatusSql.FROM_ACTIVE;
import static dev.biribiri.elysium.content.StatusSql.HAS_MEDIA;
import static dev.biribiri.elysium.content.StatusSql.NOT_A_REPLY_TO_OTHERS;
import static dev.biribiri.elysium.content.StatusSql.NOT_LOCAL_ONLY;
import static dev.biribiri.elysium.content.StatusSql.mentions;
import static dev.biribiri.elysium.content.StatusSql.notHidden;
import static dev.biribiri.elysium.content.StatusSql.taggedWith;

/**
 * Chooses the statuses in each timeline, as a page of IDs, newest first. The conditions run before paging, so that a page
 * isn't emptied after it's been chosen.
 */
@Repository
public class TimelineRepository {

    private static final String NOT_HIDDEN = notHidden(":viewer");

    private final JdbcClient jdbc;

    TimelineRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param viewer who's looking, from whom what they've hidden is left out, or null
     */
    public List<UUID> findPublic(StatusOrigin origin, IdPage page, boolean onlyMedia, UUID viewer) {
        return findPage(publicFilter(origin, onlyMedia, viewer), page);
    }

    /**
     * Whether {@link #findPublic} would list this status, so that a stream shows what the timeline does.
     */
    public boolean inPublic(UUID statusId, StatusOrigin origin, boolean onlyMedia, UUID viewer) {
        return matches(publicFilter(origin, onlyMedia, viewer), statusId);
    }

    private static Filter publicFilter(StatusOrigin origin, boolean onlyMedia, UUID viewer) {
        var filter = "s.visibility = 'public' AND s.reblog_of_id IS NULL AND " + NOT_A_REPLY_TO_OTHERS + origin(origin)
                + (onlyMedia ? HAS_MEDIA : "") + (viewer == null ? NOT_LOCAL_ONLY : NOT_HIDDEN);
        return new Filter(filter, viewer == null ? Map.of() : Map.of("viewer", viewer));
    }

    /**
     * Replies are listed too, as on Mastodon, since someone following a tag wants every status that uses it.
     */
    public List<UUID> findTagged(TagFilter tags, StatusOrigin origin, IdPage page, boolean onlyMedia, UUID viewer) {
        return findPage(taggedFilter(tags, origin, onlyMedia, viewer), page);
    }

    /**
     * Whether {@link #findTagged} would list this status.
     */
    public boolean inTagged(UUID statusId, TagFilter tags, StatusOrigin origin, boolean onlyMedia, UUID viewer) {
        return matches(taggedFilter(tags, origin, onlyMedia, viewer), statusId);
    }

    private static Filter taggedFilter(TagFilter tags, StatusOrigin origin, boolean onlyMedia, UUID viewer) {
        var parameters = new HashMap<String, Object>();
        var filter = new StringBuilder("s.visibility = 'public' AND s.reblog_of_id IS NULL AND ")
                .append(taggedWith(tags, parameters))
                .append(origin(origin));
        if (onlyMedia) {
            filter.append(HAS_MEDIA);
        }
        if (viewer != null) {
            filter.append(NOT_HIDDEN);
            parameters.put("viewer", viewer);
        } else {
            filter.append(NOT_LOCAL_ONLY);
        }
        return new Filter(filter.toString(), parameters);
    }

    private static String origin(StatusOrigin origin) {
        return switch (origin) {
            case ANY -> "";
            case LOCAL -> " AND author.is_local";
            case REMOTE -> " AND NOT author.is_local";
        };
    }

    /**
     * Replies to accounts the viewer has hidden are left out as well as their own statuses, as on Mastodon, and so is
     * what's by members of the viewer's exclusive lists.
     *
     * @param followedIds the accounts the viewer follows, which can be empty
     */
    public List<UUID> findHome(UUID viewerId, Collection<UUID> followedIds, IdPage page) {
        return findPage(homeFilter(viewerId, followedIds), page);
    }

    /**
     * Which of these accounts {@link #findHome} would list this status for, checked in one query, since a status can
     * have to be checked for everyone who's streaming their home timeline.
     */
    public List<UUID> homeViewers(UUID statusId, Collection<UUID> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        var filter = home("v.id", followed -> Following.accepted("v.id", followed));
        return jdbc.sql("SELECT v.id FROM unnest(CAST(:candidates AS uuid[])) AS v (id) WHERE EXISTS (SELECT 1 "
                        + FROM_ACTIVE + "WHERE s.id = :statusId AND " + filter + ")")
                .param("candidates", array(candidates))
                .param("statusId", statusId)
                .query(UUID.class)
                .list();
    }

    private static Filter homeFilter(UUID viewerId, Collection<UUID> followedIds) {
        var filter = home(":viewer", followed -> followed + " = ANY (CAST(:followed AS uuid[]))");
        return new Filter(filter, Map.of("viewer", viewerId, "followed", array(followedIds)));
    }

    /**
     * The home timeline's condition, for one viewer bound as a parameter or for each of several in one query.
     *
     * @param viewer   the viewer's ID, as SQL
     * @param follows  the condition that the viewer follows the account with the ID given as SQL
     */
    private static String home(String viewer, Function<String, String> follows) {
        return "(s.actor_id = " + viewer
                + " OR (" + follows.apply("s.actor_id") + " AND NOT " + ListMembers.inExclusiveList(viewer, "s.actor_id")
                + " AND ((s.visibility IN ('public', 'unlisted', 'private')"
                + " AND (NOT s.reply OR s.in_reply_to_account_id = s.actor_id OR s.in_reply_to_account_id = " + viewer
                + " OR " + follows.apply("s.in_reply_to_account_id") + "))"
                + " OR (s.visibility = 'direct' AND" + mentions(viewer) + "))))" + notHidden(viewer)
                + " AND " + HiddenAccounts.visibleInTimelinesById("s.in_reply_to_account_id", viewer);
    }

    /**
     * Lists what the home timeline would of the list's members, as on Mastodon, except for direct statuses, which
     * Mastodon doesn't add to lists, and with replies chosen by the list's policy. Members whose follow request hasn't
     * been accepted are left out, since the owner couldn't see their followers-only statuses.
     */
    public List<UUID> findList(AccountList list, IdPage page) {
        return findPage(new Filter(list(":viewer", ":list", ":policy"), Map.of("viewer", list.ownerId(), "list",
                list.id(), "policy", list.repliesPolicy().value())), page);
    }

    /**
     * Which of these lists {@link #findList} would list this status in, checked in one query, as {@link #homeViewers}
     * does.
     */
    public List<UUID> listsShowing(UUID statusId, Collection<AccountList> lists) {
        if (lists.isEmpty()) {
            return List.of();
        }
        var all = List.copyOf(lists);
        return jdbc.sql("SELECT v.list_id FROM unnest(CAST(:lists AS uuid[]), CAST(:owners AS uuid[]),"
                        + " CAST(:policies AS text[])) AS v (list_id, owner_id, policy) WHERE EXISTS (SELECT 1 "
                        + FROM_ACTIVE + "WHERE s.id = :statusId AND " + list("v.owner_id", "v.list_id", "v.policy") + ")")
                .param("lists", array(all.stream().map(AccountList::id).toList()))
                .param("owners", array(all.stream().map(AccountList::ownerId).toList()))
                .param("policies", all.stream().map(list -> list.repliesPolicy().value()).toArray(String[]::new))
                .param("statusId", statusId)
                .query(UUID.class)
                .list();
    }

    /**
     * A list's condition. A reply to the owner is always listed, as on Mastodon.
     *
     * @param owner  the list owner's ID, as SQL
     * @param list   the list's ID, as SQL
     * @param policy its replies policy's value, as SQL
     */
    private static String list(String owner, String list, String policy) {
        return "(" + ListMembers.inList(list, "s.actor_id") + " AND " + Following.accepted(owner, "s.actor_id")
                + " AND s.visibility IN ('public', 'unlisted', 'private')"
                + " AND (NOT s.reply OR s.in_reply_to_account_id = s.actor_id OR s.in_reply_to_account_id = " + owner
                + " OR (" + policy + " = 'followed' AND " + Following.accepted(owner, "s.in_reply_to_account_id") + ")"
                + " OR (" + policy + " = 'list' AND " + ListMembers.inList(list, "s.in_reply_to_account_id") + ")))"
                + notHidden(owner) + " AND " + HiddenAccounts.visibleInTimelinesById("s.in_reply_to_account_id", owner);
    }

    /**
     * One array parameter, rather than one for each account, which PostgreSQL's driver limits the number of.
     */
    private static String array(Collection<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
    }

    /**
     * A timeline's condition on {@code s} and its author, kept in one place so that a page of the timeline and a check of
     * one status always agree.
     */
    private record Filter(String sql, Map<String, ?> parameters) {
    }

    private boolean matches(Filter filter, UUID statusId) {
        var parameters = new HashMap<String, Object>(filter.parameters());
        parameters.put("statusId", statusId);
        return Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS (SELECT 1 " + FROM_ACTIVE + "WHERE " + filter.sql()
                        + " AND s.id = :statusId)")
                .params(parameters)
                .query(Boolean.class)
                .single());
    }

    private List<UUID> findPage(Filter filter, IdPage page) {
        var parameters = new HashMap<String, Object>(filter.parameters());
        var sql = "SELECT s.id " + FROM_ACTIVE + "WHERE " + filter.sql() + page.cursors("s.id", parameters)
                + page.orderAndLimit("s.id", parameters);
        return page.newestFirst(jdbc.sql(sql).params(parameters).query(UUID.class).list());
    }
}
