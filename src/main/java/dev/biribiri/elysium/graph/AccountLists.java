package dev.biribiri.elysium.graph;

import dev.biribiri.elysium.actor.IdPage;
import dev.biribiri.elysium.graph.internal.AccountListRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Users' lists of accounts they follow. They're never federated.
 * <p>
 * Every method takes the account that owns the list, and one that another account owns reads as missing. The messages of
 * what's refused are Mastodon's, where it has them.
 */
@Service
public class AccountLists {

    /**
     * As on Mastodon.
     */
    public static final int MAX_LISTS = 50;

    /**
     * Mastodon has no limit on a title.
     */
    public static final int MAX_TITLE_LENGTH = 256;

    private final AccountListRepository lists;

    AccountLists(AccountListRepository lists) {
        this.lists = lists;
    }

    /**
     * @return the owner's lists, oldest first
     */
    public List<AccountList> all(UUID ownerId) {
        return lists.findByOwner(ownerId);
    }

    public Optional<AccountList> find(UUID ownerId, UUID listId) {
        return lists.find(ownerId, listId);
    }

    /**
     * Whoever owns them, for checking statuses against lists that their owners are already known to have.
     */
    public List<AccountList> findByIds(Collection<UUID> listIds) {
        return lists.findByIds(listIds);
    }

    /**
     * @return the owner's lists that have the account, oldest first
     */
    public List<AccountList> containing(UUID ownerId, UUID accountId) {
        return lists.findContaining(ownerId, accountId);
    }

    /**
     * @param repliesPolicy {@link AccountList.RepliesPolicy#LIST} if null, as on Mastodon
     * @param exclusive     false if null
     * @throws InvalidAccountListException if the title is refused, or the owner has as many lists as they can
     */
    @Transactional
    public AccountList create(UUID ownerId, String title, AccountList.RepliesPolicy repliesPolicy, Boolean exclusive) {
        if (title == null) throw new InvalidAccountListException("Title can't be blank");
        var list = new AccountList(UUID.randomUUID(), ownerId, title(title),
                repliesPolicy == null ? AccountList.RepliesPolicy.LIST : repliesPolicy, Boolean.TRUE.equals(exclusive));
        lists.lockOwner(ownerId);
        if (lists.countByOwner(ownerId) >= MAX_LISTS) {
            throw new InvalidAccountListException("You have reached the maximum number of lists");
        }
        lists.insert(list);
        return list;
    }

    /**
     * Changes what isn't null.
     *
     * @return the list as changed, or empty if the owner has no such list
     * @throws InvalidAccountListException if the title is refused
     */
    @Transactional
    public Optional<AccountList> update(UUID ownerId, UUID listId, String title,
                                        AccountList.RepliesPolicy repliesPolicy, Boolean exclusive) {
        var newTitle = title == null ? null : title(title);
        lists.lockOwner(ownerId);
        return lists.find(ownerId, listId).map(old -> {
            var changed = new AccountList(old.id(), ownerId, newTitle == null ? old.title() : newTitle,
                    repliesPolicy == null ? old.repliesPolicy() : repliesPolicy,
                    exclusive == null ? old.exclusive() : exclusive);
            lists.update(changed);
            return changed;
        });
    }

    /**
     * @return whether the owner had the list
     */
    @Transactional
    public boolean delete(UUID ownerId, UUID listId) {
        return lists.delete(ownerId, listId);
    }

    /**
     * Lists the list's members, newest first, leaving out suspended and deleted accounts.
     *
     * @return empty if the owner has no such list
     */
    public Optional<List<FollowEntry>> members(UUID ownerId, UUID listId, IdPage page) {
        return lists.find(ownerId, listId).map(list -> lists.findMembers(ownerId, listId, page));
    }

    /**
     * Like {@link #members}, all of them at once.
     */
    public Optional<List<FollowEntry>> allMembers(UUID ownerId, UUID listId) {
        return lists.find(ownerId, listId).map(list -> lists.findAllMembers(ownerId, listId));
    }

    /**
     * Adds all of the accounts, or none of them. An account can be added once the owner has asked to follow it, as on
     * Mastodon, though its statuses are only listed once the request is accepted.
     *
     * @return false if the owner has no such list, or doesn't follow one of the accounts, or one is suspended, deleted,
     * or unknown, which Mastodon answers alike
     * @throws InvalidAccountListException if one of the accounts is already in the list
     */
    @Transactional
    public boolean addMembers(UUID ownerId, UUID listId, Collection<UUID> accountIds) {
        lists.lockOwner(ownerId);
        if (lists.find(ownerId, listId).isEmpty()) return false;
        var distinct = new LinkedHashSet<>(accountIds);
        if (distinct.isEmpty()) return true;
        if (!lists.findMemberIds(listId, distinct).isEmpty()) {
            throw new InvalidAccountListException("Account has already been taken");
        }
        if (lists.insertMembers(ownerId, listId, distinct) < distinct.size()) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return false;
        }
        return true;
    }

    /**
     * Ignores accounts that aren't in the list.
     *
     * @return whether the owner has the list
     */
    @Transactional
    public boolean removeMembers(UUID ownerId, UUID listId, Collection<UUID> accountIds) {
        if (lists.find(ownerId, listId).isEmpty()) return false;
        if (!accountIds.isEmpty()) {
            lists.deleteMembers(listId, accountIds);
        }
        return true;
    }

    /**
     * Removes the lists an account owns. It's removed from others' lists along with its follows.
     */
    @Transactional
    public void removeForActor(UUID actorId) {
        lists.deleteByOwner(actorId);
    }

    private static String title(String title) {
        var stripped = title.strip();
        if (stripped.isEmpty()) throw new InvalidAccountListException("Title can't be blank");
        if (stripped.codePointCount(0, stripped.length()) > MAX_TITLE_LENGTH) {
            throw new InvalidAccountListException("Title is too long (maximum is " + MAX_TITLE_LENGTH + " characters)");
        }
        return stripped;
    }
}
