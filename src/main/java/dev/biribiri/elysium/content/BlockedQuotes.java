package dev.biribiri.elysium.content;

import dev.biribiri.elysium.graph.AccountBlocked;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Withdraws a local author's approval of the quotes by someone they block. A remote actor's block is for its own server to
 * act on, by deleting its {@code QuoteAuthorization}s.
 */
@Component
class BlockedQuotes {

    private final QuoteService quotes;

    BlockedQuotes(QuoteService quotes) {
        this.quotes = quotes;
    }

    @ApplicationModuleListener
    void on(AccountBlocked event) {
        quotes.revokeQuotesBy(event.blockerId(), event.blockedId());
    }
}
