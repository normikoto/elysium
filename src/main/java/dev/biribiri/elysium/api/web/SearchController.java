package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.biribiri.elysium.actor.AccountFilter;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorResolver;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.text.Hashtags;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Searches for accounts and hashtags. Statuses can't be searched yet, so that list is always empty.
 *
 * <p>A query that's an address, {@code username@domain}, or an {@code https} URL finds that one account. Only a signed-in
 * user's request with {@code resolve} can make this server ask another one about it. Any other query matches the start of
 * usernames this server knows, and of the hashtags its public statuses use.
 */
@RestController
@Tag(name = "Search", description = "Finding accounts and hashtags.",
        externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "search/"))
public class SearchController {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 40;
    private static final Pattern ACCT = Pattern.compile("@?[^@\\s]+@[^@\\s]+");
    private static final Pattern USERNAME_PREFIX = Pattern.compile("@?[A-Za-z0-9_]{1,30}");

    /**
     * How far {@code offset} can page through hashtags. Each page is counted from every public status that has a matching
     * tag, so a deep page would cost as much as a shallow one, for results nobody reads.
     */
    private static final int MAX_TAG_OFFSET = 200;

    private final ActorService actors;
    private final AccountLookup lookup;
    private final AccountResponses accounts;
    private final StatusService statuses;
    private final TagResponses tags;

    SearchController(
            ActorService actors, AccountLookup lookup, AccountResponses accounts, StatusService statuses,
            TagResponses tags) {
        this.actors = actors;
        this.lookup = lookup;
        this.accounts = accounts;
        this.statuses = statuses;
        this.tags = tags;
    }

    @GetMapping("/api/v2/search")
    @PreAuthorize("isAnonymous() or hasAuthority('SCOPE_read:search')")
    @Operation(
            operationId = "search",
            summary = "Search for accounts and hashtags",
            description = """
                    Differences from Mastodon:

                    - Statuses can't be found. `statuses` is always empty, and a `type` of `statuses` returns nothing.
                    - Hashtags are found by the start of their name, from those that public statuses stored here use, \
                    most used first. A query that's a valid tag name, with or without its `#`, is also found when \
                    nothing uses it, as the first result. Only the first\s""" + MAX_TAG_OFFSET + """
                     can be paged through with `offset`.
                    - A query that's a `username@domain` or an `https` URL finds that one account. Any other query \
                    matches the start of the usernames this server knows.
                    - Another server is only asked about an account for a signed-in user's request, when `resolve` is \
                    true. Each user can make\s""" + RemoteActorResolver.LOOKUPS_PER_MINUTE + """
                     of those lookups a minute, and then gets a 429.
                    - Only the first\s""" + AccountFilter.MAX_LIMIT + """
                     matches can be paged through with `offset`, and an `offset` past them returns no accounts.
                    - `following`, `account_id`, `exclude_unreviewed`, `min_id`, and `max_id` are ignored.

                    Suspended accounts are left out.""",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "search/#v2"),
            parameters = {
                    @Parameter(name = "q", description = """
                            A username, or the start of one, a `username@domain`, an `https` URL, or the start of a \
                            hashtag. A missing or blank query returns nothing."""),
                    @Parameter(name = "type", description = "Only `accounts` or `hashtags` returns anything.",
                            schema = @Schema(allowableValues = {"accounts", "statuses", "hashtags"})),
                    @Parameter(name = "resolve", description = """
                            Whether to ask another server about an address or URL this server doesn't know, or hasn't \
                            refreshed for a while. Only works for a signed-in user.\s""" + ApiDocs.FLAG),
                    @Parameter(name = "limit", description = "How many of each type to return. Values outside 1 to "
                            + MAX_LIMIT + " are clamped to them.",
                            schema = @Schema(type = "integer", defaultValue = "" + DEFAULT_LIMIT)),
                    @Parameter(name = "offset", description = "How many matches to skip.",
                            schema = @Schema(type = "integer", defaultValue = "0"))
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "The accounts and hashtags found."),
                    @ApiResponse(responseCode = "422", description = "`limit` or `offset` isn't a number."),
                    @ApiResponse(responseCode = "429", description = "The user has made too many lookups.")
            })
    public SearchResponse search(
            @AuthenticationPrincipal TokenPrincipal token,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String resolve,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String offset) {
        var anyType = type == null || type.isEmpty();
        var searchAccounts = anyType || type.equals("accounts");
        var searchTags = anyType || type.equals("hashtags");
        if (q == null || q.isBlank() || !(searchAccounts || searchTags)) {
            return SearchResponse.EMPTY;
        }
        var viewer = RequestParameters.viewer(token);
        var shouldResolve = RequestParameters.flag(resolve);
        var query = q.strip();
        var pageSize = Math.clamp(RequestParameters.number("limit", limit, DEFAULT_LIMIT), 1, MAX_LIMIT);
        // Only the first results can be paged through, and an offset past them gets an empty page, so that a client paging
        // by offset sees the end rather than the last page again.
        var requestedOffset = Math.max(RequestParameters.number("offset", offset, 0), 0);
        var skipped = Math.min(requestedOffset, AccountFilter.MAX_LIMIT);

        var foundTags = searchTags ? tags(query, pageSize, requestedOffset, viewer.isPresent()) : List.<TagResponse>of();
        if (!searchAccounts) {
            return new SearchResponse(List.of(), List.of(), foundTags);
        }

        List<Actor> found;
        if (query.regionMatches(true, 0, "https://", 0, "https://".length())) {
            found = skipped > 0 ? List.of() : lookup.byUrl(query, viewer, shouldResolve).stream().toList();
        } else if (ACCT.matcher(query).matches()) {
            found = skipped > 0 ? List.of() : lookup.byAcct(query, viewer, shouldResolve).stream().toList();
        } else if (USERNAME_PREFIX.matcher(query).matches()) {
            found = byUsernamePrefix(query.startsWith("@") ? query.substring(1) : query, pageSize, skipped);
        } else {
            found = List.of();
        }
        return new SearchResponse(accounts.build(found), List.of(), foundTags);
    }

    /**
     * As on Mastodon, a query that names a tag finds it even when nothing uses it, so that a client can open its timeline
     * or offer it while someone's writing.
     */
    private List<TagResponse> tags(String query, int pageSize, int skipped, boolean signedIn) {
        var exact = Hashtags.parse(query);
        if (exact.isEmpty() || skipped >= MAX_TAG_OFFSET) {
            return List.of();
        }
        var names = new ArrayList<>(statuses.searchTags(exact.get(), Math.min(pageSize, MAX_TAG_OFFSET - skipped), skipped));
        if (skipped == 0 && !names.contains(exact.get())) {
            names.addFirst(exact.get());
            if (names.size() > pageSize) {
                names.removeLast();
            }
        }
        return tags.build(names, signedIn);
    }

    private List<Actor> byUsernamePrefix(String prefix, int pageSize, int skipped) {
        // The filter loads at most its maximum, which leaves nothing after a large offset.
        var filter = new AccountFilter(null, false, prefix, null, pageSize + skipped, null, null, null);
        return actors.list(filter).stream().filter(Actor::isActive).skip(skipped).limit(pageSize).toList();
    }

    /**
     * Mastodon's Search entity.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "Search", description = "Mastodon's Search entity. `statuses` is always empty.")
    record SearchResponse(List<AccountResponse> accounts, List<Object> statuses, List<TagResponse> hashtags) {

        static final SearchResponse EMPTY = new SearchResponse(List.of(), List.of(), List.of());
    }
}
