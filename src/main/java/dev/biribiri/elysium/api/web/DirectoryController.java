package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.content.AccountDirectory;
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

import java.util.List;

@RestController
@Tag(name = "Directory", description = "The accounts that choose to be listed.",
        externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "directory/"))
public class DirectoryController {

    private final AccountDirectory directory;
    private final AccountResponses accounts;

    DirectoryController(AccountDirectory directory, AccountResponses accounts) {
        this.directory = directory;
        this.accounts = accounts;
    }

    @GetMapping("/api/v1/directory")
    @PreAuthorize("isAnonymous() or hasAuthority('SCOPE_read:accounts')")
    @Operation(
            operationId = "getDirectory",
            summary = "List the accounts in the directory",
            description = """
                    Lists the accounts whose `discoverable` is true, from this server and others, as on Mastodon. \
                    Suspended, deleted, and moved accounts, and those on suspended servers, are excluded. For a \
                    signed-in user, so are the accounts they've blocked or muted, or blocked the server of, and those \
                    that have blocked them.

                    Differences from Mastodon: an `order` other than `active` or `new` gets 422, and `new` lists \
                    accounts on other servers by when this server first stored them. Gets 404 when the operator has \
                    turned the directory off.""",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "directory/#get"),
            parameters = {
                    @Parameter(name = "offset", description = "How many accounts to skip.",
                            schema = @Schema(type = "integer", defaultValue = "0")),
                    @Parameter(name = "limit", description = "How many accounts to return. Values outside 1 to "
                            + PageLimits.MAX_ACCOUNTS + " are clamped to them.",
                            schema = @Schema(type = "integer", defaultValue = "" + PageLimits.ACCOUNTS)),
                    @Parameter(name = "order", description = """
                            `active` lists the accounts that posted most recently first, and those that never have \
                            last. `new` lists the newest accounts first.""",
                            schema = @Schema(allowableValues = {"active", "new"}, defaultValue = "active")),
                    @Parameter(name = "local", description = "Whether to list only this server's accounts.\s"
                            + ApiDocs.FLAG)
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "The accounts."),
                    @ApiResponse(responseCode = "404", description = "The directory is turned off."),
                    @ApiResponse(responseCode = "422", description = """
                            `limit` or `offset` isn't a number, or `order` isn't `active` or `new`.""")
            })
    public List<AccountResponse> list(
            @AuthenticationPrincipal TokenPrincipal token,
            @RequestParam(required = false) String offset,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String order,
            @RequestParam(required = false) String local) {
        var pageSize = Math.clamp(RequestParameters.number("limit", limit, PageLimits.ACCOUNTS), 1,
                PageLimits.MAX_ACCOUNTS);
        var skipped = Math.max(RequestParameters.number("offset", offset, 0), 0);
        var sort = AccountDirectory.Order.parse(order).orElseThrow(
                () -> new InvalidRequestException("Validation failed: order must be active or new"));
        return accounts.build(directory.list(RequestParameters.viewer(token), sort, RequestParameters.flag(local), skipped,
                pageSize).orElseThrow(RecordNotFoundException::new));
    }
}
