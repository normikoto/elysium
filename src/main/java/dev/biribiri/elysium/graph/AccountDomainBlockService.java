package dev.biribiri.elysium.graph;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.graph.internal.AccountDomainBlockRepository;
import dev.biribiri.elysium.graph.internal.FollowRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Local accounts' blocks of whole servers, including their subdomains. These hide the servers' accounts from the account,
 * remove its followers there, and refuse their requests to follow it, but aren't federated, and leave the account's own
 * follows of them, as on Mastodon. They're separate from the instance's domain blocks, which admins make.
 */
@Service
public class AccountDomainBlockService {

    private final AccountDomainBlockRepository domainBlocks;
    private final FollowRepository followRows;
    private final FollowService follows;
    private final ActorService actors;

    AccountDomainBlockService(AccountDomainBlockRepository domainBlocks, FollowRepository followRows, FollowService follows,
                              ActorService actors) {
        this.domainBlocks = domainBlocks;
        this.followRows = followRows;
        this.follows = follows;
        this.actors = actors;
    }

    /**
     * Blocks a server for a local account, and removes the account's followers there, each with a {@link FollowerRemoved}.
     * Blocking a server already blocked does nothing.
     *
     * @param domain a domain as {@code moderation.DomainName.parse} leaves it
     */
    @Transactional
    public void block(UUID accountId, String domain) {
        // Before any pair lock, as FollowService.receive takes it, so that a follower can't be added while this is saved.
        followRows.lock(FollowService.serverBlockKey(accountId));
        if (!domainBlocks.insert(accountId, domain)) {
            return;
        }
        for (var follower : domainBlocks.findBlockedFollowers(accountId)) {
            followRows.lock(FollowService.pairKey(follower.uri(), accountId));
            actors.findById(follower.id()).ifPresent(actor -> follows.removeFollowerLocked(actor, accountId));
        }
    }

    /**
     * @return whether the server was blocked
     */
    @Transactional
    public boolean unblock(UUID accountId, String domain) {
        return domainBlocks.delete(accountId, domain);
    }

    /**
     * Lists the servers an account has blocked, newest first.
     */
    public List<Entry> page(UUID accountId, DomainBlockListPage page) {
        return domainBlocks.findPage(accountId, page.maxId(), page.sinceId(), page.minId(), page.limit()).stream()
                .map(entry -> new Entry(entry.id(), entry.domain()))
                .toList();
    }

    /**
     * Removes the servers an account has blocked.
     */
    public void removeForAccount(UUID accountId) {
        domainBlocks.deleteForAccount(accountId);
    }

    /**
     * @param id what to page from, with {@link DomainBlockListPage}
     */
    public record Entry(UUID id, String domain) {
    }
}
