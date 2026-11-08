package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.RegistrationProperties;
import dev.biribiri.elysium.mail.MailNotSentException;
import dev.biribiri.elysium.mail.Mailer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Sign-ups from the sign-up page. One with an invite creates an account straight away. One without waits in
 * {@code pending_sign_up} until its email address is confirmed, and then until an admin approves it if the registration mode
 * asks for that. A pending sign-up has no actor, so nothing else on the server can see it, and it can't log in.
 */
@Service
class SignUps {

    static final int MAX_EMAIL_LENGTH = 254;
    static final int MAX_REASON_LENGTH = 1000;

    /**
     * How long a link to confirm an address works. An unconfirmed sign-up holds on to its username and address until then,
     * so this can't be very long. The sign-up pages and emails say "a day".
     */
    static final Duration CONFIRMATION_EXPIRY = Duration.ofDays(1);

    /**
     * Only catches typing mistakes. Whether an address works is decided by confirming it.
     */
    private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");

    private static final Logger log = LoggerFactory.getLogger(SignUps.class);

    private final LocalAccounts accounts;
    private final ActorService actors;
    private final Invites invites;
    private final Mailer mailer;
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final RegistrationProperties registration;
    private final InstanceProperties instance;

    SignUps(
            LocalAccounts accounts,
            ActorService actors,
            Invites invites,
            Mailer mailer,
            JdbcClient jdbc,
            TransactionTemplate transaction,
            RegistrationProperties registration,
            InstanceProperties instance) {
        this.accounts = accounts;
        this.actors = actors;
        this.invites = invites;
        this.mailer = mailer;
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.registration = registration;
        this.instance = instance;
    }

    static boolean isValidEmail(String email) {
        return email != null && email.length() <= MAX_EMAIL_LENGTH && EMAIL.matcher(email).matches();
    }

    /**
     * Creates an account with an invite.
     *
     * @return whether it was created, which it won't be if the username is taken
     * @throws InviteUnusableException      if the invite doesn't work, in which case nothing is created
     * @throws FirstAccountRefusedException if no other account exists yet
     * @throws PasswordHashingBusyException if too many passwords are being hashed already
     */
    boolean signUpWithInvite(String invite, String username, String password) {
        var hash = accounts.hash(password);
        return accounts.create(username, hash, null, false, () -> invites.use(invite)).isPresent();
    }

    /**
     * Stores a sign-up without an invite, and emails a link to confirm its address.
     *
     * @param reason why they want to join, for an admin to read, or null
     * @throws MailNotSentException         if the email couldn't be sent, in which case nothing is stored
     * @throws PasswordHashingBusyException if too many passwords are being hashed already
     */
    Requested request(String username, String email, String password, String reason) {
        var hash = accounts.hash(password);
        var token = SecretCodes.generate();
        var id = UUID.randomUUID();
        var result = transaction.execute(status -> {
            accounts.lockAccountCreation();
            jdbc.sql("DELETE FROM pending_sign_up WHERE verified_at IS NULL AND created_at <= :cutoff")
                    .param("cutoff", cutoff())
                    .update();
            // Suspended and deleted accounts keep their usernames.
            if (actors.findAnyLocalByUsername(username).isPresent() || accounts.isUsernamePending(username)) {
                return Requested.USERNAME_TAKEN;
            }
            if (accounts.isEmailTaken(email)) {
                return Requested.EMAIL_TAKEN;
            }
            jdbc.sql("""
                            INSERT INTO pending_sign_up (id, username, email, password_hash, reason, verification_token_hash)
                            VALUES (:id, :username, :email, :hash, :reason, :tokenHash)
                            """)
                    .param("id", id)
                    .param("username", username)
                    .param("email", email)
                    .param("hash", hash)
                    .param("reason", reason == null || reason.isBlank() ? null : reason.strip())
                    .param("tokenHash", SecretCodes.hash(token))
                    .update();
            return Requested.SENT;
        });
        if (result != Requested.SENT) {
            return result;
        }

        try {
            mailer.send(email, "Confirm your email address for " + instance.domain(), confirmationEmail(username, token));
        } catch (RuntimeException e) {
            // Nobody can confirm it, and it would hold on to the username and address until it expired.
            jdbc.sql("DELETE FROM pending_sign_up WHERE id = :id").param("id", id).update();
            throw e;
        }
        return Requested.SENT;
    }

    /**
     * Finds the sign-up that an emailed link is for, if it still works.
     */
    Optional<Pending> findUnconfirmed(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        SELECT * FROM pending_sign_up
                        WHERE verification_token_hash = :tokenHash AND created_at > :cutoff
                        """)
                .param("tokenHash", SecretCodes.hash(token))
                .param("cutoff", cutoff())
                .query((rs, rowNum) -> pending(rs))
                .optional();
    }

    /**
     * Confirms a sign-up's email address. It becomes an account unless an admin has to approve it first.
     *
     * @throws FirstAccountRefusedException if no other account exists
     */
    Confirmed confirm(String token) {
        var found = findUnconfirmed(token);
        if (found.isEmpty()) {
            return Confirmed.NOT_FOUND;
        }
        var pending = found.get();
        if (registration.approvalRequired()) {
            var confirmed = jdbc.sql("""
                            UPDATE pending_sign_up
                            SET verified_at = now(), verification_token_hash = NULL
                            WHERE id = :id AND verification_token_hash IS NOT NULL
                            """)
                    .param("id", pending.id())
                    .update() > 0;
            return confirmed ? Confirmed.AWAITING_APPROVAL : Confirmed.NOT_FOUND;
        }
        return complete(pending, false) ? Confirmed.CREATED : Confirmed.NOT_FOUND;
    }

    /**
     * @return the sign-ups that have confirmed their address and are waiting for an admin, oldest first
     */
    List<Pending> awaitingApproval() {
        return jdbc.sql("SELECT * FROM pending_sign_up WHERE verified_at IS NOT NULL ORDER BY verified_at, id")
                .query((rs, rowNum) -> pending(rs))
                .list();
    }

    /**
     * Finds a sign-up that's waiting for an admin, by the username it asked for.
     */
    Optional<Pending> findAwaitingApproval(String username) {
        return jdbc.sql("SELECT * FROM pending_sign_up WHERE verified_at IS NOT NULL AND lower(username) = lower(:username)")
                .param("username", username)
                .query((rs, rowNum) -> pending(rs))
                .optional();
    }

    /**
     * Turns a sign-up that's waiting for an admin into an account, and tells them by email.
     *
     * @return whether it was waiting, and has become an account
     * @throws FirstAccountRefusedException if no other account exists
     */
    boolean approve(UUID id) {
        return pendingById(id).filter(Pending::confirmed).map(pending -> complete(pending, true)).orElse(false);
    }

    /**
     * Removes a sign-up that's waiting for an admin, and tells them by email.
     *
     * @return whether it was waiting
     */
    boolean reject(UUID id) {
        var removed = jdbc.sql("""
                        DELETE FROM pending_sign_up WHERE id = :id AND verified_at IS NOT NULL
                        RETURNING username, email
                        """)
                .param("id", id)
                .query((rs, rowNum) -> new String[]{rs.getString("username"), rs.getString("email")})
                .optional();
        removed.ifPresent(found -> sendQuietly(found[1], "Your sign-up for " + instance.domain(), """
                Your request for the account @%s on %s wasn't approved.
                """.formatted(found[0], instance.domain())));
        return removed.isPresent();
    }

    private boolean complete(Pending pending, boolean approved) {
        Optional<AccountRole> created;
        try {
            created = accounts.create(pending.username(), pending.passwordHash(), pending.email(), false, () -> {
                // Removing it first frees its username and address for the account. Nothing removed means another
                // request completed or rejected it first.
                var removed = jdbc.sql("DELETE FROM pending_sign_up WHERE id = :id")
                        .param("id", pending.id())
                        .update();
                if (removed == 0) {
                    throw new AlreadyHandledException();
                }
            });
        } catch (AlreadyHandledException e) {
            return false;
        }
        if (created.isEmpty()) {
            return false;
        }
        if (approved) {
            sendQuietly(pending.email(), "Your account on " + instance.domain() + " is ready", """
                    Your account @%s on %s has been approved. You can log in at %s
                    """.formatted(pending.username(), instance.domain(), loginUrl()));
        }
        return true;
    }

    private Optional<Pending> pendingById(UUID id) {
        return jdbc.sql("SELECT * FROM pending_sign_up WHERE id = :id")
                .param("id", id)
                .query((rs, rowNum) -> pending(rs))
                .optional();
    }

    /**
     * For messages that only tell someone what's already happened, which a failure to send shouldn't undo.
     */
    private void sendQuietly(String to, String subject, String body) {
        try {
            mailer.send(to, subject, body);
        } catch (MailNotSentException e) {
            log.warn("Couldn't email a sign-up about its result", e);
        }
    }

    private String confirmationEmail(String username, String token) {
        var link = UriComponentsBuilder.fromUri(instance.baseUrl())
                .path(SecurityConfig.SIGNUP_VERIFY_PATH)
                .queryParam("token", token)
                .build()
                .toUri();
        var next = registration.approvalRequired()
                ? "After that, an admin has to approve the account before you can log in."
                : "After that, you can log in.";
        return """
                Someone, hopefully you, asked for the account @%s on %s with this email address.

                To confirm it, open this link:
                %s

                %s The link stops working after a day. If this wasn't you, ignore this email, and no account will be made.
                """.formatted(username, instance.domain(), link, next);
    }

    private String loginUrl() {
        return instance.resolve(SecurityConfig.LOGIN_PATH).toString();
    }

    private OffsetDateTime cutoff() {
        return Instant.now().minus(CONFIRMATION_EXPIRY).atOffset(ZoneOffset.UTC);
    }

    private static Pending pending(ResultSet rs) throws SQLException {
        var verifiedAt = rs.getTimestamp("verified_at");
        return new Pending(
                rs.getObject("id", UUID.class),
                rs.getString("username"),
                rs.getString("email"),
                rs.getString("password_hash"),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant(),
                verifiedAt == null ? null : verifiedAt.toInstant());
    }

    private static final class AlreadyHandledException extends RuntimeException {

        AlreadyHandledException() {
            super(null, null, false, false);
        }
    }

    enum Requested {
        SENT, USERNAME_TAKEN, EMAIL_TAKEN
    }

    enum Confirmed {
        CREATED, AWAITING_APPROVAL, NOT_FOUND
    }

    record Pending(
            UUID id,
            String username,
            String email,
            String passwordHash,
            String reason,
            Instant createdAt,
            Instant confirmedAt) {

        boolean confirmed() {
            return confirmedAt != null;
        }

        @Override
        public String toString() {
            return "Pending[id=" + id + ", username=" + username + "]";
        }
    }
}
