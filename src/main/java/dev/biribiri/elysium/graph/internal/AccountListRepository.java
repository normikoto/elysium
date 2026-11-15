package dev.biribiri.elysium.graph.internal;

import dev.biribiri.elysium.graph.AccountList;
import dev.biribiri.elysium.graph.FollowEntry;
import dev.biribiri.elysium.graph.FollowPage;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

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
        return Pages.findAll(jdbc, "account_list_member", " AND r.list_id = :list", Map.of("list", listId), ownerId);
    }

    /**
     * @return which of these accounts are already in the list
     */
    public List<UUID> findMemberIds(UUID listId, Collection<UUID> accountIds) {
        return jdbc.sql("""
                        SELECT target_id FROM account_list_member
                        WHERE list_id = :list AND target_id = ANY (CAST(:accounts AS uuid[]))""")
                .param("list", listId).param("accounts", accountIds.toArray(UUID[]::new)).query(UUID.class).list();
    }

    /**
     * Adds each account that the owner follows or has asked to, and that's neither suspended nor deleted. Reading the
     * follows in the same statement means they can't be removed in between, which the foreign key would refuse.
     *
     * @param accountIds distinct
     * @return how many were added
     */
    public int insertMembers(UUID ownerId, UUID listId, Collection<UUID> accountIds) {
        return jdbc.sql("""
                        INSERT INTO account_list_member (id, list_id, account_id, target_id, created_at)
                        SELECT v.id, :list, f.follower_id, f.followed_id, now()
                        FROM unnest(CAST(:ids AS uuid[]), CAST(:accounts AS uuid[])) AS v (id, account_id)
                        JOIN follow f ON f.follower_id = :owner AND f.followed_id = v.account_id
                        JOIN actor a ON a.id = f.followed_id AND a.suspended_at IS NULL AND a.deleted_at IS NULL""")
                .param("ids", accountIds.stream().map(id -> UUID.randomUUID()).toArray(UUID[]::new))
                .param("accounts", accountIds.toArray(UUID[]::new))
                .param("list", listId)
                .param("owner", ownerId)
                .update();
    }

    public void deleteMembers(UUID listId, Collection<UUID> accountIds) {
        jdbc.sql("DELETE FROM account_list_member WHERE list_id = :list AND target_id = ANY (CAST(:accounts AS uuid[]))")
                .param("list", listId).param("accounts", accountIds.toArray(UUID[]::new)).update();
    }
}
