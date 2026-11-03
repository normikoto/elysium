package dev.biribiri.elysium.auth.internal;

import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs each call in a transaction, so that it uses one database connection. The JDBC service looks up the client while
 * it's reading an authorization, which otherwise takes a second connection while holding the first. Once every connection
 * in the pool is held that way, those requests wait on each other until they time out.
 */
class SingleConnectionAuthorizationService implements OAuth2AuthorizationService {

    private final OAuth2AuthorizationService delegate;
    private final TransactionTemplate transaction;

    SingleConnectionAuthorizationService(OAuth2AuthorizationService delegate, TransactionTemplate transaction) {
        this.delegate = delegate;
        this.transaction = transaction;
    }

    @Override
    public void save(OAuth2Authorization authorization) {
        transaction.executeWithoutResult(status -> delegate.save(authorization));
    }

    @Override
    public void remove(OAuth2Authorization authorization) {
        transaction.executeWithoutResult(status -> delegate.remove(authorization));
    }

    @Override
    public OAuth2Authorization findById(String id) {
        return transaction.execute(status -> delegate.findById(id));
    }

    @Override
    public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
        return transaction.execute(status -> delegate.findByToken(token, tokenType));
    }
}
