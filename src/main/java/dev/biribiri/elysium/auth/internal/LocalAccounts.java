package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.ActorService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Passwords and roles for local actors.
 */
@Service
class LocalAccounts implements UserDetailsService {

    static final int MIN_PASSWORD_LENGTH = 8;
    static final int MAX_PASSWORD_LENGTH = 128;

    /**
     * Identifies the advisory lock that's held while deciding whether a new account is the first, and whether a username or
     * email address is free. A username has to be free of both accounts and {@link SignUps pending sign-ups}, which are in
     * different tables, so no unique index can decide that. PostgreSQL's advisory locks are shared by the whole database, so
     * this is just an arbitrary number that nothing else uses.
     */
    private static final long ACCOUNT_CREATION_LOCK = 7_391_046_215L;

    /**
     * Identifies the advisory lock that's held while deciding whether an account can be suspended or deleted, so that two
     * admins can't each remove the other.
     */
    private static final long LAST_ADMIN_LOCK = 7_391_046_216L;

    private static final String ACTIVE_ADMINS = """
            account_credential c
            JOIN actor a ON a.id = c.actor_id
            WHERE c.role = 'ADMIN' AND a.suspended_at IS NULL AND a.deleted_at IS NULL
            """;

    private final ActorService actors;
    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transaction;

    LocalAccounts(
            ActorService actors, JdbcClient jdbc, PasswordEncoder passwordEncoder, TransactionTemplate transaction) {
        this.actors = actors;
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.transaction = transaction;
    }

    static boolean isValidPassword(String password) {
        return password != null
                && password.length() >= MIN_PASSWORD_LENGTH
                && password.length() <= MAX_PASSWORD_LENGTH;
    }

    /**
     * Loads a user by the username they typed. The returned user's name is the actor ID, because that's what tokens
     * are recorded against. It has to be Spring's own {@link User} class, because Spring only deserializes an
     * allowlist of types when it reads an authorization back from the database.
     */
    @Override
    public UserDetails loadUserByUsername(String username) {
        return actors.findLocalByUsername(username)
                .flatMap(actor -> credential(actor.id()).map(credential -> User.withUsername(actor.id().toString())
                        .password(credential.passwordHash())
                        .roles(credential.role() == AccountRole.ADMIN ? new String[]{"USER", "ADMIN"}
                                : new String[]{"USER"})
                        .build()))
                .orElseThrow(() -> new UsernameNotFoundException("No local account with that username"));
    }

    /**
     * Creates a new account for someone using the sign-up page.
     *
     * @return whether it was created, which it won't be if the username is taken
     * @throws FirstAccountRefusedException if no other account exists yet, in which case nothing is created
     */
    boolean signUp(String username, String password) {
        return create(username, hash(password), null, false, () -> {
        }).isPresent();
    }

    /**
     * Creates a new account on behalf of the server's operator. The server's first account becomes an admin.
     *
     * @return the new account's role, or empty if the username is taken
     */
    Optional<AccountRole> createAccount(String username, String password) {
        return create(username, hash(password), null, true, () -> {
        });
    }

    /**
     * Whether nobody has an account yet, so that the sign-up page would refuse to create one. This is only for turning
     * people away early. It's the check made while creating an account that counts, since this can be out of date by the
     * time an account is created.
     */
    boolean hasNoAccounts() {
        return !actors.hasLocalAccounts();
    }

    /**
     * Whether an account is an admin right now. Access checks have to use this, rather than the authorities of a login
     * session or a stored authorization. Those are copies from when they were created, and a session's copy is serialized,
     * so a command can't update it when the role changes.
     */
    boolean isAdmin(UUID actorId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM account_credential WHERE actor_id = :actorId AND role = 'ADMIN')")
                .param("actorId", actorId)
                .query(Boolean.class)
                .single();
    }

    /**
     * Changes an account's role. If that changes anything, this also revokes the account's applications that have admin
     * scopes: their tokens, pending authorization codes, and the consent that would let them get new codes without
     * asking. Those scopes only work while the account is an admin, so without this, removing the role and later giving it
     * back would restore old tokens. Giving the role for the first time would also bring to life any admin scopes that
     * the account approved as a regular user.
     *
     * @return what happened, or empty if the account has no password, which is where its role is stored
     */
    Optional<RoleChange> setRole(UUID actorId, AccountRole role) {
        return transaction.execute(status -> {
            if (credential(actorId).isEmpty()) {
                return Optional.empty();
            }
            var changed = jdbc.sql("""
                            UPDATE account_credential
                            SET role = :role, updated_at = now()
                            WHERE actor_id = :actorId AND role <> :role
                            """)
                    .param("actorId", actorId)
                    .param("role", role.name())
                    .update() > 0;
            var revoked = changed && revokeAdminGrants(actorId);
            var adminsRemain = jdbc.sql("SELECT EXISTS (SELECT 1 FROM " + ACTIVE_ADMINS + ")")
                    .query(Boolean.class)
                    .single();
            return Optional.of(new RoleChange(changed, revoked, adminsRemain));
        });
    }

    private boolean revokeAdminGrants(UUID actorId) {
        var authorizations = jdbc.sql("""
                        DELETE FROM oauth2_authorization
                        WHERE principal_name = :principalName
                          AND (authorized_scopes LIKE '%admin:%' OR access_token_scopes LIKE '%admin:%')
                        """)
                .param("principalName", actorId.toString())
                .update();
        var consents = jdbc.sql("""
                        DELETE FROM oauth2_authorization_consent
                        WHERE principal_name = :principalName AND authorities LIKE '%admin:%'
                        """)
                .param("principalName", actorId.toString())
                .update();
        return authorizations + consents > 0;
    }

    /**
     * Checks and hashes a password for {@link #create}. Hashing is slow, so this mustn't be called inside a transaction.
     *
     * @throws IllegalArgumentException     if the password isn't valid
     * @throws PasswordHashingBusyException if too many passwords are being hashed already
     */
    String hash(String password) {
        requireValidPassword(password);
        return passwordEncoder.encode(password);
    }

    /**
     * Creates an account.
     *
     * @param passwordHash  from {@link #hash}
     * @param email         a confirmed address, or null
     * @param inTransaction runs in the same transaction, once the lock is held and before the username and email address are
     *                      checked, so that whatever it changes is undone if the account isn't created. If it throws, nothing is
     *                      created.
     * @return the new account's role, or empty if the username or email address is taken
     * @throws FirstAccountRefusedException if no other account exists yet and {@code mayBeFirst} is false
     */
    Optional<AccountRole> create(
            String username, String passwordHash, String email, boolean mayBeFirst, Runnable inTransaction) {
        var role = new AtomicReference<AccountRole>();
        try {
            var created = actors.createLocalIfAbsent(username, actor -> {
                // It's not a table lock because this transaction has already written to the actor table, so two of them
                // upgrading their locks could deadlock.
                lockAccountCreation();
                removeExpiredSignUps();
                inTransaction.run();
                if (isUsernamePending(username) || (email != null && isEmailTaken(email))) {
                    throw new TakenException();
                }
                // This includes the account being created. It also includes suspended accounts, since one of those isn't
                // a reason to make a stranger an admin.
                var first = actors.countExistingLocal() == 1;
                if (first && !mayBeFirst) {
                    throw new FirstAccountRefusedException();
                }
                role.set(first ? AccountRole.ADMIN : AccountRole.USER);
                insertCredential(actor.id(), passwordHash, role.get(), email);
            });
            return created.map(actor -> role.get());
        } catch (TakenException e) {
            return Optional.empty();
        }
    }

    /**
     * Makes other transactions wait here until this one ends, before they decide whether an account can be created: see
     * {@link #ACCOUNT_CREATION_LOCK}. Each transaction can only see the others' accounts once they've committed, so only one
     * at a time can decide. This has to be a separate statement from the checks, so that they see whatever was committed
     * while waiting.
     */
    void lockAccountCreation() {
        jdbc.sql("SELECT pg_advisory_xact_lock(:key)").param("key", ACCOUNT_CREATION_LOCK).query(rs -> {
        });
    }

    /**
     * Frees the usernames and addresses of sign-ups whose links have expired. Callers that then check those hold
     * {@link #lockAccountCreation}, so that the answer is current. {@link SignUpCleanupTask} doesn't need it, since removing
     * expired sign-ups can only free a name, never take one.
     */
    void removeExpiredSignUps() {
        jdbc.sql("DELETE FROM pending_sign_up WHERE verified_at IS NULL AND created_at <= :cutoff")
                .param("cutoff", Instant.now().minus(SignUps.CONFIRMATION_EXPIRY).atOffset(ZoneOffset.UTC))
                .update();
    }

    /**
     * Whether an account or a pending sign-up has this email address. Only {@link #lockAccountCreation} keeps the answer
     * true until the transaction ends.
     */
    boolean isEmailTaken(String email) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM account_credential WHERE lower(email) = lower(:email))
                            OR EXISTS (SELECT 1 FROM pending_sign_up WHERE lower(email) = lower(:email))
                        """)
                .param("email", email)
                .query(Boolean.class)
                .single();
    }

    /**
     * Whether a pending sign-up has this username. Suspended and deleted accounts keep theirs too, which the actor table
     * decides.
     */
    boolean isUsernamePending(String username) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM pending_sign_up WHERE lower(username) = lower(:username))")
                .param("username", username)
                .query(Boolean.class)
                .single();
    }

    /**
     * Thrown inside the transaction, to undo creating the actor.
     */
    private static final class TakenException extends RuntimeException {

        TakenException() {
            super(null, null, false, false);
        }
    }

    /**
     * Sets an actor's password, replacing any existing one, ends its login sessions, and revokes its applications' access: their
     * tokens, pending authorization codes, and the consent that would let them get new codes without asking.
     */
    void resetPassword(UUID actorId, String password) {
        requireValidPassword(password);
        // Hashing is slow, so it happens before the transaction takes a database connection.
        var hash = passwordEncoder.encode(password);
        transaction.executeWithoutResult(status -> {
            jdbc.sql("""
                            INSERT INTO account_credential (actor_id, password_hash)
                            VALUES (:actorId, :hash)
                            ON CONFLICT (actor_id) DO UPDATE
                            SET password_hash = EXCLUDED.password_hash, updated_at = now()
                            """)
                    .param("actorId", actorId)
                    .param("hash", hash)
                    .update();
            endSessions(actorId);
            revokeGrants(actorId);
        });
    }

    /**
     * Logs an account out of every browser, in the caller's transaction, including any login that's in progress, which is why this
     * also moves on its generation: see {@link LoginGenerationFilter}. A session's principal name is the actor ID: see
     * {@link #loadUserByUsername}. Attributes go with it through the foreign key.
     *
     * @return the account's new generation, which a session started afterward in the same transaction belongs to, or empty if it
     * has no password
     */
    Optional<Long> endSessions(UUID actorId) {
        var generation = jdbc.sql("""
                        UPDATE account_credential SET login_generation = login_generation + 1 WHERE actor_id = :actorId
                        RETURNING login_generation
                        """)
                .param("actorId", actorId)
                .query(Long.class)
                .optional();
        jdbc.sql("DELETE FROM spring_session WHERE principal_name = :principalName")
                .param("principalName", actorId.toString())
                .update();
        return generation;
    }

    /**
     * @return the generation that a login by this username would belong to, if the account can log in
     * @see LoginGenerationFilter
     */
    Optional<Long> loginGeneration(String username) {
        return actors.findLocalByUsername(username).flatMap(actor -> loginGeneration(actor.id()));
    }

    /**
     * @return how many times this account's sessions have been ended, if it has a password
     * @see LoginGenerationFilter
     */
    Optional<Long> loginGeneration(UUID actorId) {
        return jdbc.sql("SELECT login_generation FROM account_credential WHERE actor_id = :actorId")
                .param("actorId", actorId)
                .query(Long.class)
                .optional();
    }

    private void revokeGrants(UUID actorId) {
        jdbc.sql("DELETE FROM oauth2_authorization WHERE principal_name = :principalName")
                .param("principalName", actorId.toString())
                .update();
        jdbc.sql("DELETE FROM oauth2_authorization_consent WHERE principal_name = :principalName")
                .param("principalName", actorId.toString())
                .update();
    }

    /**
     * Removes an account's password, role, login sessions, and everything its applications have, in the caller's transaction. The signing
     * key stays, because an account's last activities still have to be signed.
     */
    void removeCredentials(UUID actorId) {
        jdbc.sql("DELETE FROM account_credential WHERE actor_id = :actorId").param("actorId", actorId).update();
        endSessions(actorId);
        revokeGrants(actorId);
    }

    /**
     * Makes other transactions wait here until this one ends, before they decide with {@link #isLastActiveAdmin}. This has to
     * be a separate statement from that one, so that it sees whatever was committed while waiting.
     */
    void lockAdmins() {
        jdbc.sql("SELECT pg_advisory_xact_lock(:key)").param("key", LAST_ADMIN_LOCK).query(rs -> {
        });
    }

    /**
     * Whether an account is an active admin, with no other one to take over from it.
     */
    boolean isLastActiveAdmin(UUID actorId) {
        var activeAdmins = jdbc.sql("SELECT c.actor_id FROM " + ACTIVE_ADMINS).query(UUID.class).list();
        return activeAdmins.size() == 1 && activeAdmins.contains(actorId);
    }

    /**
     * @return the admins whose accounts aren't suspended or deleted
     */
    Set<UUID> activeAdmins() {
        return Set.copyOf(jdbc.sql("SELECT c.actor_id FROM " + ACTIVE_ADMINS).query(UUID.class).list());
    }

    /**
     * @return which of these accounts have the admin role
     */
    Set<UUID> adminsAmong(Collection<UUID> actorIds) {
        if (actorIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbc.sql("SELECT actor_id FROM account_credential WHERE role = 'ADMIN' AND actor_id IN (:ids)")
                .param("ids", actorIds)
                .query(UUID.class)
                .list());
    }

    /**
     * Checks an account's password. This is slow, so it mustn't be called inside a transaction.
     *
     * @throws PasswordHashingBusyException if too many passwords are being checked already
     */
    boolean passwordMatches(UUID actorId, String password) {
        return password != null && credential(actorId)
                .filter(found -> passwordEncoder.matches(password, found.passwordHash()))
                .isPresent();
    }

    private void insertCredential(UUID actorId, String hash, AccountRole role, String email) {
        jdbc.sql("""
                        INSERT INTO account_credential (actor_id, password_hash, role, email)
                        VALUES (:actorId, :hash, :role, :email)
                        """)
                .param("actorId", actorId)
                .param("hash", hash)
                .param("role", role.name())
                .param("email", email)
                .update();
    }

    /**
     * @return which of these accounts have an email address, and what it is
     */
    Map<UUID, String> emailsOf(Collection<UUID> actorIds) {
        if (actorIds.isEmpty()) {
            return Map.of();
        }
        var emails = new HashMap<UUID, String>();
        jdbc.sql("SELECT actor_id, email FROM account_credential WHERE email IS NOT NULL AND actor_id IN (:ids)")
                .param("ids", actorIds)
                .query((rs, rowNum) -> emails.put(rs.getObject("actor_id", UUID.class), rs.getString("email")))
                .list();
        return emails;
    }

    private Optional<Credential> credential(UUID actorId) {
        return jdbc.sql("SELECT password_hash, role FROM account_credential WHERE actor_id = :actorId")
                .param("actorId", actorId)
                .query((rs, rowNum) -> new Credential(
                        rs.getString("password_hash"), AccountRole.valueOf(rs.getString("role"))))
                .optional();
    }

    private static void requireValidPassword(String password) {
        if (!isValidPassword(password)) {
            throw new IllegalArgumentException("Passwords must be %d to %d characters"
                    .formatted(MIN_PASSWORD_LENGTH, MAX_PASSWORD_LENGTH));
        }
    }

    private record Credential(String passwordHash, AccountRole role) {
    }

    /**
     * The result of setting an account's role.
     *
     * @param changed      whether the account had a different role before
     * @param revokedApps  whether any of the account's applications lost their access
     * @param adminsRemain whether the server has any admins now
     */
    record RoleChange(boolean changed, boolean revokedApps, boolean adminsRemain) {
    }
}
