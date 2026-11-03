package dev.biribiri.elysium.auth.internal;

import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs each call in a transaction, for the same reason as {@link SingleConnectionAuthorizationService}.
 */
class SingleConnectionAuthorizationConsentService implements OAuth2AuthorizationConsentService {

    private final OAuth2AuthorizationConsentService delegate;
    private final TransactionTemplate transaction;

    SingleConnectionAuthorizationConsentService(
            OAuth2AuthorizationConsentService delegate, TransactionTemplate transaction) {
        this.delegate = delegate;
        this.transaction = transaction;
    }

    @Override
    public void save(OAuth2AuthorizationConsent consent) {
        transaction.executeWithoutResult(status -> delegate.save(consent));
    }

    @Override
    public void remove(OAuth2AuthorizationConsent consent) {
        transaction.executeWithoutResult(status -> delegate.remove(consent));
    }

    @Override
    public OAuth2AuthorizationConsent findById(String registeredClientId, String principalName) {
        return transaction.execute(status -> delegate.findById(registeredClientId, principalName));
    }
}
