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
     * {@link QuoteReview#NONE} until the account chooses, so that quotes are approved automatically, as on Mastodon.
     */
    public QuoteReview quoteReview(UUID actorId) {
        return repository.findQuoteReview(actorId).flatMap(QuoteReview::fromValue).orElse(QuoteReview.NONE);
    }

    public void setQuoteReview(UUID actorId, QuoteReview review) {
        repository.setQuoteReview(actorId, review.value());
    }

    public void removeFor(UUID actorId) {
        repository.removeFor(actorId);
    }
}
