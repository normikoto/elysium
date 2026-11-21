package dev.biribiri.elysium.api.web;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Mastodon's FamiliarFollowers entity.
 *
 * @param id       the account asked about
 * @param accounts who among the accounts the user follows also follows it
 */
@Schema(name = "FamiliarFollowers", description = "Mastodon's FamiliarFollowers entity.")
record FamiliarFollowersResponse(String id, List<AccountResponse> accounts) {
}
