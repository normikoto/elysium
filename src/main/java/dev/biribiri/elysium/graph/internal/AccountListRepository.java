package dev.biribiri.elysium.graph.internal;

import dev.biribiri.elysium.graph.AccountList;
import dev.biribiri.elysium.graph.FollowEntry;
import dev.biribiri.elysium.graph.FollowPage;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AccountListRepository {

    private static final String SELECT = "SELECT id, owner_id, title, replies_policy, exclusive FROM account_list ";

    private static final RowMapper<AccountList> MAPPER = (rs, row) -> new AccountList(
            rs.getObject("id", UUID.class),
            rs.getObject("owner_id", UUID.class),
            rs.getString("title"),
            AccountList.RepliesPolicy.parse(rs.getString("replies_policy")).orElseThrow(),
            rs.getBoolean("exclusive"));

    /**
     * Members that can be listed: not suspended or deleted, as with blocks and mutes.
     */
    private static final String ACTIVE_MEMBER = "JOIN actor a ON a.id = m.target_id"
            + " AND a.suspended_at IS NULL AND a.deleted_at IS NULL ";

    private final JdbcClient jdbc;

    AccountListRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Makes another change to the owner's lists wait until this transaction ends, so that limits are counted correctly.
     */
    public void lockOwner(UUID ownerId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "account_list\n" + ownerId).query((rs, row) -> true).single();
    }

    /**
     * @return the owner's lists, oldest first
     */
    public List<AccountList> findByOwner(UUID ownerId) {
        return jdbc.sql(SELECT + "WHERE owner_id = :owner ORDER BY created_at, id")
                .param("owner", ownerId).query(MAPPER).list();
    }

    public Optional<AccountList> find(UUID ownerId, UUID listId) {
        return jdbc.sql(SELECT + "WHERE owner_id = :owner AND id = :id")
                .param("owner", ownerId).param("id", listId).query(MAPPER).optional();
    }

    public List<AccountList> findByIds(Collection<UUID> listIds) {
        if (listIds.isEmpty()) return List.of();
        return jdbc.sql(SELECT + "WHERE id IN (:ids)").param("ids", listIds).query(MAPPER).list();
    }

    /**
     * @return the owner's lists that have the account, oldest first
     */
    public List<AccountList> findContaining(UUID ownerId, UUID accountId) {
        return jdbc.sql(SELECT + """
                        WHERE owner_id = :owner AND EXISTS (SELECT 1 FROM account_list_member m
                                                            WHERE m.list_id = account_list.id AND m.target_id = :account)
                        ORDER BY created_at, id""")
                .param("owner", ownerId).param("account", accountId).query(MAPPER).list();
    }

    public int countByOwner(UUID ownerId) {
        return jdbc.sql("SELECT count(*) FROM account_list WHERE owner_id = :owner")
                .param("owner", ownerId).query(Integer.class).single();
    }

    public void insert(AccountList list) {
        jdbc.sql("""
                        INSERT INTO account_list (id, owner_id, title, replies_policy, exclusive, created_at)
                        VALUES (:id, :owner, :title, :policy, :exclusive, now())""")
                .param("id", list.id())
                .param("owner", list.ownerId())
                .param("title", list.title())
                .param("policy", list.repliesPolicy().value())
                .param("exclusive", list.exclusive())
                .update();
    }

    public void update(AccountList list) {
        jdbc.sql("""
                        UPDATE account_list SET title = :title, replies_policy = :policy, exclusive = :exclusive
                        WHERE id = :id AND owner_id = :owner""")
                .param("id", list.id())
                .param("owner", list.ownerId())
                .param("title", list.title())
                .param("policy", list.repliesPolicy().value())
                .param("exclusive", list.exclusive())
                .update();
    }

    public boolean delete(UUID ownerId, UUID listId) {
        return jdbc.sql("DELETE FROM account_list WHERE owner_id = :owner AND id = :id")
                .param("owner", ownerId).param("id", listId).update() > 0;
    }

    public void deleteByOwner(UUID ownerId) {
        jdbc.sql("DELETE FROM account_list WHERE owner_id = :owner").param("owner", ownerId).update();
    }

    /**
     * A page of a list's members, newest first, with the member rows' IDs as cursors.
     */
    public List<FollowEntry> findMembers(UUID ownerId, UUID listId, FollowPage page) {
        return Pages.find(jdbc, "account_list_member", " AND r.list_id = :list", Map.of("list", listId), ownerId, page);
    }

    /**
     * Every one of a list's members, newest first.
     */
    public List<FollowEntry> findAllMembers(UUID ownerId, UUID listId) {
        return jdbc.sql("SELECT m.id, a.id AS other_id, a.uri AS other_uri FROM account_list_member m " + ACTIVE_MEMBER
                        + "WHERE m.account_id = :owner AND m.list_id = :list ORDER BY m.created_at DESC, m.id DESC")
                .param("owner", ownerId)
                .param("list", listId)
                .query((rs, row) -> new FollowEntry(rs.getObject("id", UUID.class), rs.getObject("other_id", UUID.class),
                        URI.create(rs.getString("other_uri"))))
                .list();
    }

    /**
     * @return which of these accounts are already in the list
     */
    public List<UUID> findMemberIds(UUID listId, Collection<UUID> accountIds) {
        return jdbc.sql("SELECT target_id FROM account_list_member WHERE list_id = :list AND target_id IN (:accounts)")
                .param("list", listId).param("accounts", accountIds).query(UUID.class).list();
    }

    /**
     * Adds the account if the owner follows it or has asked to, and it's neither suspended nor deleted. Reading the
     * follow in the same statement means it can't be removed in between, which the foreign key would refuse.
     *
     * @return whether it was added
     */
    public boolean insertMember(UUID ownerId, UUID listId, UUID accountId) {
        return jdbc.sql("""
                        INSERT INTO account_list_member (id, list_id, account_id, target_id, created_at)
                        SELECT :id, :list, f.follower_id, f.followed_id, now() FROM follow f
                        JOIN actor a ON a.id = f.followed_id AND a.suspended_at IS NULL AND a.deleted_at IS NULL
                        WHERE f.follower_id = :owner AND f.followed_id = :account""")
                .param("id", UUID.randomUUID())
                .param("list", listId)
                .param("owner", ownerId)
                .param("account", accountId)
                .update() > 0;
    }

    public void deleteMembers(UUID listId, Collection<UUID> accountIds) {
        jdbc.sql("DELETE FROM account_list_member WHERE list_id = :list AND target_id IN (:accounts)")
                .param("list", listId).param("accounts", accountIds).update();
    }
}
