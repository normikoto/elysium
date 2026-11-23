package dev.biribiri.elysium.api.web;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Every field is a string, as Mastodon sends them, since clients parse them as such.
 */
@Schema(name = "WeeklyCounts", description = """
        A week's counts, which Mastodon calls an Activity. Every value is a string of digits.""")
record WeeklyCountsResponse(
        @Schema(description = "When the week starts, in seconds since the epoch: midnight UTC on a Monday.") String week,
        @Schema(description = """
                Statuses that local accounts published that week, leaving out direct statuses and boosts. Deleted \
                statuses, and those of suspended accounts, don't count.""") String statuses,
        @Schema(description = "Local accounts that used the server that week, through the API or a login session.")
        String logins,
        @Schema(description = "Accounts created that week that haven't since been deleted.") String registrations) {
}
