package dev.biribiri.elysium.push.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;
import java.util.Optional;

/**
 * The server's VAPID key pair. It's created the first time it's needed, because SQL migrations can't generate keys, and
 * never changes after that, so it's only read once.
 */
@Component
public class ServerKeys {

    private final JdbcClient jdbc;
    private final TransactionTemplate ownTransaction;
    private volatile Keys keys;

    ServerKeys(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        // As in InstanceActorKeys: a caller's transaction could roll back the key after it's been handed out, or have a
        // view of the database from before another instance of this server inserted its own.
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.ownTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * @param publicKey as an uncompressed point
     */
    public record Keys(byte[] publicKey, PrivateKey privateKey) {

        public String publicKeyBase64Url() {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey);
        }

        @Override
        public String toString() {
            return "Keys[publicKey=" + publicKeyBase64Url() + "]";
        }
    }

    public Keys get() {
        var loaded = keys;
        if (loaded != null) {
            return loaded;
        }
        synchronized (this) {
            if (keys == null) {
                keys = load();
            }
            return keys;
        }
    }

    private Keys load() {
        var existing = ownTransaction.execute(status -> read());
        if (existing.isPresent()) {
            return existing.get();
        }
        KeyPair generated = P256.generate();
        return ownTransaction.execute(status -> {
            jdbc.sql("""
                            INSERT INTO push_server_key (public_key, private_key)
                            VALUES (:publicKey, :privateKey)
                            ON CONFLICT DO NOTHING
                            """)
                    .param("publicKey", P256.encode((ECPublicKey) generated.getPublic()))
                    .param("privateKey", generated.getPrivate().getEncoded())
                    .update();
            // Not the generated key, because another instance of this server may have inserted its own first.
            return read().orElseThrow();
        });
    }

    private Optional<Keys> read() {
        return jdbc.sql("SELECT public_key, private_key FROM push_server_key")
                .query((row, n) -> new Keys(row.getBytes("public_key"), P256.decodePrivate(row.getBytes("private_key"))))
                .optional();
    }
}
