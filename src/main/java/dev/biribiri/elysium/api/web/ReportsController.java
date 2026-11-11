package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.moderation.ReportCategory;
import dev.biribiri.elysium.moderation.Reports;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Follows Mastodon's reports API. A report goes to this server's admins, and to the reported account's server only if the
 * user asks.
 */
@RestController
@RequestMapping(ReportsController.PATH)
@Tag(name = "Reports", description = "Reporting accounts and statuses to this server's admins.",
        externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "reports/"))
public class ReportsController {

    static final String PATH = "/api/v1/reports";

    /**
     * Room for the longest comment, the most statuses, and the other fields.
     */
    private static final long MAX_BODY_BYTES = 64 * 1024;

    private final Reports reports;
    private final ActorService actors;
    private final StatusService statuses;
    private final AccountResponses accounts;
    private final JsonBodies json;

    ReportsController(Reports reports, ActorService actors, StatusService statuses, AccountResponses accounts,
                      JsonBodies json) {
        this.reports = reports;
        this.actors = actors;
        this.statuses = statuses;
        this.accounts = accounts;
        this.json = json;
    }

    @PostMapping(consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.MULTIPART_FORM_DATA_VALUE})
    @PreAuthorize("hasAuthority('SCOPE_write:reports')")
    public ReportResponse createFromForm(
            @AuthenticationPrincipal TokenPrincipal token, @RequestParam MultiValueMap<String, String> form) {
        return create(token, withLists(form));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_write:reports')")
    // The API docs combine both mappings into one operation, and this replaces what the other one documents.
    @Operation(
            operationId = "createReport",
            summary = "Report an account",
            description = """
                    Reports an account, and some of its statuses, to this server's admins. With `forward`, a report of \
                    an account on another server is also sent to that server, without saying who sent it.

                    Differences from Mastodon:

                    - There are no rules, so a `category` of `violation`, or any `rule_ids`, gets a 422.
                    - A status that isn't the account's, or that the user can't see, gets a 404 rather than being \
                    left out.
                    - A report can include at most 100 statuses, and more get a 422.
                    - Reporting your own account gets a 422.
                    - `forward_to_domains` is ignored: only the account's own server is sent the report.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "reports/#post"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The report."),
                    @ApiResponse(responseCode = "404", description = """
                            The account isn't found or is suspended, or a status isn't found, isn't the account's, or \
                            can't be seen by the user."""),
                    @ApiResponse(responseCode = "413", description = "The body is too large."),
                    @ApiResponse(responseCode = "422", description = """
                            `account_id` is missing, the account is the user's own, the comment is too long, there are \
                            too many statuses, or the category or rules aren't allowed.""")
            })
    @RequestBody(required = true, content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ReportBody.class)))
    public ReportResponse createFromJson(@AuthenticationPrincipal TokenPrincipal token, HttpServletRequest request)
            throws IOException {
        return create(token, json.read(request, MAX_BODY_BYTES));
    }

    private ReportResponse create(TokenPrincipal token, Map<String, ?> parameters) {
        var reporter = token.actorId().flatMap(actors::findLocalById).orElseThrow(InvalidRequestException::requiresUser);
        var accountId = RequestParameters.string(parameters, "account_id");
        if (accountId == null) {
            throw new InvalidRequestException("Validation failed: account_id is required");
        }
        var target = RequestParameters.uuid(accountId)
                .flatMap(actors::findById)
                .filter(Actor::isActive)
                .orElseThrow(RecordNotFoundException::new);
        if (!ids(parameters.get("rule_ids"), "rule_ids").isEmpty()) {
            throw new InvalidRequestException("Validation failed: this server has no rules, so rule_ids can't be given");
        }
        var category = category(RequestParameters.string(parameters, "category"));
        var statusIds = statusIds(parameters.get("status_ids"));
        if (statusIds.size() > Reports.MAX_STATUSES) {
            throw new InvalidRequestException(
                    "Validation failed: a report can't include more than %d statuses".formatted(Reports.MAX_STATUSES));
        }
        var visible = statuses.findVisible(statusIds, reporter.id()).stream()
                .filter(status -> status.actorId().equals(target.id()))
                .map(Status::id)
                .collect(Collectors.toSet());
        if (!visible.containsAll(statusIds)) {
            throw new RecordNotFoundException();
        }
        var report = reports.file(reporter.id(), target, statusIds, RequestParameters.string(parameters, "comment"),
                category, RequestParameters.flag(parameters, "forward"));
        return ReportResponse.from(report, accounts.one(target));
    }

    private static ReportCategory category(String value) {
        if (value == null) {
            return ReportCategory.OTHER;
        }
        if (value.equals("violation")) {
            throw new InvalidRequestException("Validation failed: this server has no rules, so violation can't be chosen");
        }
        return ReportCategory.fromValue(value).orElseThrow(() ->
                new InvalidRequestException("Validation failed: category must be spam, legal, or other"));
    }

    /**
     * An ID that isn't a UUID can't be a status, so it gets a 404, the same as one that isn't found.
     */
    private static List<UUID> statusIds(Object value) {
        var ids = new LinkedHashSet<UUID>();
        for (var id : ids(value, "status_ids")) {
            ids.add(RequestParameters.uuid(id).orElseThrow(RecordNotFoundException::new));
        }
        return List.copyOf(ids);
    }

    private static List<String> ids(Object value, String name) {
        List<?> values = switch (value) {
            case null -> List.of();
            case String string -> List.of(string);
            case List<?> list -> list;
            default -> throw new InvalidRequestException("Validation failed: " + name + " must be a list of IDs");
        };
        var ids = new ArrayList<String>();
        for (var id : values) {
            switch (id) {
                case String string when string.isEmpty() -> {
                }
                case String string -> ids.add(string);
                // Mastodon's rule IDs are numbers, which a JSON body can send as they are.
                case Number number -> ids.add(number.toString());
                default -> throw new InvalidRequestException("Validation failed: " + name + " must be a list of IDs");
            }
        }
        return ids;
    }

    /**
     * A form repeats {@code status_ids[]} for each ID, which a single value for each name would lose.
     */
    private static Map<String, Object> withLists(MultiValueMap<String, String> form) {
        var parameters = new HashMap<String, Object>(form.toSingleValueMap());
        for (var name : List.of("status_ids", "rule_ids")) {
            parameters.remove(name + "[]");
            var values = new ArrayList<String>();
            values.addAll(form.getOrDefault(name, List.of()));
            values.addAll(form.getOrDefault(name + "[]", List.of()));
            parameters.put(name, values);
        }
        return parameters;
    }

    /**
     * Only documents the body, which is read by hand.
     */
    @Schema(name = "ReportCreate")
    private record ReportBody(
            @Schema(name = "account_id", requiredMode = Schema.RequiredMode.REQUIRED) String accountId,
            @ArraySchema(arraySchema = @Schema(name = "status_ids")) List<String> statusIds,
            @Schema(maxLength = Reports.MAX_COMMENT_LENGTH) String comment,
            @Schema(defaultValue = "false") Boolean forward,
            @Schema(allowableValues = {"spam", "legal", "other"}, defaultValue = "other") String category) {
    }
}
