package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.content.RemoteThreadFetcher;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.ThreadRefresh;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Mastodon's async refreshes, which only ever describe a thread being fetched here. An ID only names its status, so
 * anyone could make one up, and it's only found for a user who can see the status.
 */
@RestController
@RequestMapping("/api/v1_alpha/async_refreshes")
@Tag(name = "Async refreshes", description = "Checking on a thread that's being fetched from other servers.",
        externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "async_refreshes/"))
public class AsyncRefreshesController {

    static final String HEADER = "Mastodon-Async-Refresh";

    /**
     * How long a client should wait before asking again, as Mastodon tells it for a thread.
     */
    static final int RETRY_SECONDS = 5;

    private static final String CONTEXT_PREFIX = "context-";

    private final StatusService statuses;
    private final RemoteThreadFetcher threads;

    AsyncRefreshesController(StatusService statuses, RemoteThreadFetcher threads) {
        this.statuses = statuses;
        this.threads = threads;
    }

    static String headerValue(ThreadRefresh refresh) {
        return "id=\"" + id(refresh) + "\", retry=" + RETRY_SECONDS + ", result_count=" + refresh.resultCount();
    }

    private static String id(ThreadRefresh refresh) {
        return CONTEXT_PREFIX + refresh.statusId();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_read:statuses')")
    @Operation(
            operationId = "getAsyncRefresh",
            summary = "Check on an async refresh",
            description = """
                    Its ID comes from the `Mastodon-Async-Refresh` header of \
                    `GET /api/v1/statuses/:id/context`, the only endpoint that sends one. A refresh counts as \
                    finished once nothing more is waiting to be fetched, or 15 minutes after it started, and is found \
                    for a day, or until the thread is fetched again. `result_count` counts the parents and replies it stored, including ones the user \
                    can't see.

                    Differences from Mastodon: `read:statuses` is enough, rather than `read`.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "async_refreshes/#show"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The refresh."),
                    @ApiResponse(responseCode = "404", description = """
                            Not found, it's too old, or the user can't see the status it's for.""")
            })
    public AsyncRefreshBody get(
            @AuthenticationPrincipal TokenPrincipal token,
            @Parameter(description = "The refresh's ID, from the header.") @PathVariable String id) {
        var viewer = NotificationRequests.viewer(token);
        return Optional.of(id)
                .filter(found -> found.startsWith(CONTEXT_PREFIX))
                .flatMap(found -> RequestParameters.uuid(found.substring(CONTEXT_PREFIX.length())))
                .filter(statusId -> statuses.findVisible(statusId, Optional.of(viewer)).isPresent())
                .flatMap(threads::find)
                .map(refresh -> new AsyncRefreshBody(new AsyncRefreshResponse(
                        id(refresh), refresh.running() ? "running" : "finished", refresh.resultCount())))
                .orElseThrow(RecordNotFoundException::new);
    }

    record AsyncRefreshBody(@JsonProperty("async_refresh") AsyncRefreshResponse asyncRefresh) {
    }

    @Schema(name = "AsyncRefresh", description = "Mastodon's AsyncRefresh entity.")
    record AsyncRefreshResponse(
            String id,
            @Schema(allowableValues = {"running", "finished"}) String status,
            @Schema(description = "How many statuses it's stored so far.") @JsonProperty("result_count") int resultCount) {
    }
}
