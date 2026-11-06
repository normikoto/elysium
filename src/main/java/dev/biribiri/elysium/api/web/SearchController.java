package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.biribiri.elysium.actor.AccountFilter;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.auth.TokenPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Searches for accounts. Statuses and hashtags can't be searched yet, so those lists are always empty.
 *
 * <p>A query that's an address, {@code username@domain}, or an {@code https} URL finds that one account. Only a signed-in
 * user's request with {@code resolve} can make this server ask another one about it. Any other query matches the start of
 * usernames this server knows.
 */
@RestController
public class SearchController {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 40;
    private static final Pattern ACCT = Pattern.compile("@?[^@\\s]+@[^@\\s]+");
    private static final Pattern USERNAME_PREFIX = Pattern.compile("@?[A-Za-z0-9_]{1,30}");

    private final ActorService actors;
    private final AccountLookup lookup;
    private final AccountResponses accounts;

    SearchController(ActorService actors, AccountLookup lookup, AccountResponses accounts) {
        this.actors = actors;
        this.lookup = lookup;
        this.accounts = accounts;
    }

    @GetMapping("/api/v2/search")
    @PreAuthorize("isAnonymous() or hasAuthority('SCOPE_read:search')")
    public SearchResponse search(
            @AuthenticationPrincipal TokenPrincipal token,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String resolve,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String offset) {
        if (q == null || q.isBlank() || (type != null && !type.isEmpty() && !type.equals("accounts"))) {
            return SearchResponse.EMPTY;
        }
        var viewer = RequestParameters.viewer(token);
        var shouldResolve = RequestParameters.flag(resolve);
        var query = q.strip();
        var pageSize = Math.clamp(RequestParameters.number("limit", limit, DEFAULT_LIMIT), 1, MAX_LIMIT);
        // Only the first results can be paged through, and an offset past them gets an empty page, so that a client paging
        // by offset sees the end rather than the last page again.
        var skipped = Math.clamp(RequestParameters.number("offset", offset, 0), 0, AccountFilter.MAX_LIMIT);

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
        return new SearchResponse(accounts.build(found), List.of(), List.of());
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
    record SearchResponse(List<AccountResponse> accounts, List<Object> statuses, List<Object> hashtags) {

        static final SearchResponse EMPTY = new SearchResponse(List.of(), List.of(), List.of());
    }
}
