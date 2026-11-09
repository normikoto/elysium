package dev.biribiri.elysium.content;

import java.time.LocalDate;

/**
 * How much a hashtag was used on one day, in UTC, counting public statuses stored here.
 *
 * @param uses     how many statuses had it
 * @param accounts how many accounts posted them
 */
public record TagDay(LocalDate day, long uses, long accounts) {
}
