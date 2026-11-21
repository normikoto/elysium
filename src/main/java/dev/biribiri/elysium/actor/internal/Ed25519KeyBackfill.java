package dev.biribiri.elysium.actor.internal;

import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.UUID;

/**
 * Migration 68, which gives every local account and the instance actor an Ed25519 keypair. It's in Java because SQL
 * can't generate the keys, and it generates them all up front so that the columns can be {@code NOT NULL}, rather than
 * every reader having to make a key for an account that doesn't have one yet. Spring passes it to Flyway as a bean, and
 * no file in {@code db/migration} can take its version.
 *
 * <p>An instance actor that hasn't been created yet gets its keys from {@link InstanceActorKeys} when it is.
 */
@Component
class Ed25519KeyBackfill implements JavaMigration {

    private static final int BATCH_SIZE = 1000;

    @Override
    public MigrationVersion getVersion() {
        return MigrationVersion.fromVersion("68");
    }

    @Override
    public String getDescription() {
        return "ed25519 keys";
    }

    @Override
    public Integer getChecksum() {
        return null;
    }

    @Override
    public boolean canExecuteInTransaction() {
        return true;
    }

    @Override
    public void migrate(Context context) throws SQLException {
        var connection = context.getConnection();
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    ALTER TABLE local_account
                        ADD COLUMN ed25519_public_key_pem  TEXT,
                        ADD COLUMN ed25519_private_key_pem TEXT
                    """);
            statement.execute("""
                    ALTER TABLE instance_actor
                        ADD COLUMN ed25519_public_key_pem  TEXT,
                        ADD COLUMN ed25519_private_key_pem TEXT
                    """);
        }
        fillLocalAccounts(connection);
        fillInstanceActor(connection);
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    ALTER TABLE local_account
                        ALTER COLUMN ed25519_public_key_pem SET NOT NULL,
                        ALTER COLUMN ed25519_private_key_pem SET NOT NULL
                    """);
            statement.execute("""
                    ALTER TABLE instance_actor
                        ALTER COLUMN ed25519_public_key_pem SET NOT NULL,
                        ALTER COLUMN ed25519_private_key_pem SET NOT NULL
                    """);
        }
    }

    private static void fillLocalAccounts(Connection connection) throws SQLException {
        var ids = new ArrayList<UUID>();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT actor_id FROM local_account")) {
            while (rows.next()) {
                ids.add(rows.getObject(1, UUID.class));
            }
        }
        try (var update = connection.prepareStatement("""
                UPDATE local_account SET ed25519_public_key_pem = ?, ed25519_private_key_pem = ? WHERE actor_id = ?
                """)) {
            var pending = 0;
            for (var id : ids) {
                var keys = Ed25519Keys.generate();
                update.setString(1, keys.publicKeyPem());
                update.setString(2, keys.privateKeyPem());
                update.setObject(3, id);
                update.addBatch();
                if (++pending == BATCH_SIZE) {
                    update.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                update.executeBatch();
            }
        }
    }

    private static void fillInstanceActor(Connection connection) throws SQLException {
        var keys = Ed25519Keys.generate();
        try (var update = connection.prepareStatement("""
                UPDATE instance_actor SET ed25519_public_key_pem = ?, ed25519_private_key_pem = ?
                """)) {
            update.setString(1, keys.publicKeyPem());
            update.setString(2, keys.privateKeyPem());
            update.executeUpdate();
        }
    }
}
