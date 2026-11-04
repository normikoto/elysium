package dev.biribiri.elysium.activitypub.signature;

import java.util.Optional;

/**
 * How many key fetches a request's sender is allowed to cause. Anyone can name a key on any host, and the limits in
 * {@link PublicKeyResolver} are shared by everyone, so this is what stops one sender from using them up.
 *
 * <p>Only a request that starts a fetch uses this. A key that's cached, a failure that's remembered, and a fetch that
 * another request already started all cost nothing, and neither does a fetch this server refuses for reasons of its own.
 */
@FunctionalInterface
public interface KeyFetchBudget {

    /**
     * @return a reservation for one fetch, or empty if the sender can't start another one right now
     */
    Optional<Reservation> tryReserve();

    interface Reservation {

        /**
         * The fetch has finished, so it's no longer running. It still counts as having been made.
         */
        void finished();

        /**
         * The fetch was never made, so it shouldn't count at all.
         */
        void cancelled();
    }
}
