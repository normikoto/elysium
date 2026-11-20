package dev.biribiri.elysium.content;

import dev.biribiri.elysium.content.internal.PostingDefaultRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * What a local account's new statuses get when its client doesn't say.
 */
@Service
public class PostingDefaults {

    private final PostingDefaultRepository repository;

    PostingDefaults(PostingDefaultRepository repository) {
        this.repository = repository;
    }

    /**
     * {@link QuotePolicy#PUBLIC} until the account chooses, as on Mastodon.
     */
    public QuotePolicy quotePolicy(UUID actorId) {
        return repository.findQuotePolicy(actorId).flatMap(QuotePolicy::fromValue).orElse(QuotePolicy.PUBLIC);
    }

    public void setQuotePolicy(UUID actorId, QuotePolicy policy) {
        repository.setQuotePolicy(actorId, policy.value());
    }

    /**
     * {@link OtherQuotes#REJECT} until the account chooses, as on Mastodon.
     */
    public OtherQuotes quoteOthers(UUID actorId) {
        return repository.findQuoteOthers(actorId).flatMap(OtherQuotes::fromValue).orElse(OtherQuotes.REJECT);
    }

    public void setQuoteOthers(UUID actorId, OtherQuotes others) {
        repository.setQuoteOthers(actorId, others.value());
    }

    public void removeFor(UUID actorId) {
        repository.removeFor(actorId);
    }
}
