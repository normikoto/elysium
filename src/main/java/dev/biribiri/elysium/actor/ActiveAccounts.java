package dev.biribiri.elysium.actor;

import dev.biribiri.elysium.actor.internal.AccountUseRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which local accounts use the server, by UTC day, for the active account counts that NodeInfo and Mastodon's instance API
 * report. A day is as fine as those counts need, and keeps what's stored about when someone is around coarse.
 */
@Service
public class ActiveAccounts {

    public static final int MONTH_DAYS = 30;

    public static final int HALF_YEAR_DAYS = 180;

    /**
     * Long enough for the half-year count, and for the twelve weeks of the instance API.
     */
    static final int DAYS_KEPT = HALF_YEAR_DAYS;

    private final AccountUseRepository uses;

    /**
     * The day each account's use was last recorded by this node, so that an account writes once a day rather than on each
     * request. Another node writing the same day is harmless, since the insert ignores a day that's already there. It holds
     * one entry per local account that's used the server since this node started.
     */
    private final Map<UUID, LocalDate> recorded = new ConcurrentHashMap<>();

    ActiveAccounts(AccountUseRepository uses) {
        this.uses = uses;
    }

    /**
     * Records that a local account used the server today. It's called on each authenticated request, so it only touches
     * the database the first time each day.
     */
    public void recordUse(UUID actorId) {
        var today = today();
        if (today.equals(recorded.get(actorId))) {
            return;
        }
        uses.insert(actorId, today);
        recorded.put(actorId, today);
    }

    /**
     * Counts the active accounts that used the server on any of the last {@code days} days, today included.
     */
    public long countActiveWithin(int days) {
        return uses.countAccountsSince(today().minusDays(days - 1L));
    }

    /**
     * @return how many active accounts used the server in each week since {@code since}, keyed by the Monday the week
     * starts on. Weeks with none are left out.
     */
    public Map<LocalDate, Long> countByWeek(LocalDate since) {
        return uses.countAccountsByWeek(since);
    }

    void removeExpired() {
        uses.deleteBefore(today().minusDays(DAYS_KEPT));
    }

    void removeForActor(UUID actorId) {
        uses.deleteForActor(actorId);
        recorded.remove(actorId);
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }
}
