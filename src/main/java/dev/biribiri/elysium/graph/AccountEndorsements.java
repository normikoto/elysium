package dev.biribiri.elysium.graph;

import dev.biribiri.elysium.actor.IdPage;
import dev.biribiri.elysium.graph.internal.AccountEndorsementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Local accounts' endorsements of accounts they follow, which Mastodon calls account pins or featured accounts. As on
 * Mastodon, an account can only endorse one it follows, and the endorsement ends with the follow, however that ends,
 * including when the followed account moves. Endorsements are shown in {@link Relationship} and never federated.
 */
@Service
public class AccountEndorsements {

    private final AccountEndorsementRepository endorsements;

    AccountEndorsements(AccountEndorsementRepository endorsements) {
        this.endorsements = endorsements;
    }

    /**
     * Endorsing an account already endorsed changes nothing.
     *
     * @return false if the account doesn't follow the target, or has only asked to
     */
    @Transactional
    public boolean endorse(UUID accountId, UUID targetId) {
        return endorsements.save(accountId, targetId);
    }

    public void unendorse(UUID accountId, UUID targetId) {
        endorsements.delete(accountId, targetId);
    }

    /**
     * Lists the accounts an account endorses, newest first, leaving out suspended and deleted ones. The cursors are
     * endorsement IDs.
     */
    public List<FollowEntry> endorsed(UUID accountId, IdPage page) {
        return endorsements.findPage(accountId, page);
    }
}
