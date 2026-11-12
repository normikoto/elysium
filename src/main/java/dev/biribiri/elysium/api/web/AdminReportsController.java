package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.moderation.Report;
import dev.biribiri.elysium.moderation.ReportPage;
import dev.biribiri.elysium.moderation.Reports;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Follows Mastodon's admin API for reports. Reported statuses are shown whoever they were for, since reporting one shares
 * it with admins on purpose.
 */
@RestController
@RequestMapping(AdminReportsController.PATH)
@Tag(name = "Admin: reports", description = "Handling reports from users and other servers. Needs an admin's token.",
        externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "admin/reports/"))
public class AdminReportsController {

    static final String PATH = "/api/v1/admin/reports";

    private static final long MAX_BODY_BYTES = 4 * 1024;

    private final Reports reports;
    private final ActorService actors;
    private final StatusService statuses;
    private final StatusResponses statusResponses;
    private final AdminAccountResponses accounts;
    private final InstanceProperties instance;
    private final JsonBodies json;

    AdminReportsController(Reports reports, ActorService actors, StatusService statuses, StatusResponses statusResponses,
                           AdminAccountResponses accounts, InstanceProperties instance, JsonBodies json) {
        this.reports = reports;
        this.actors = actors;
        this.statuses = statuses;
        this.statusResponses = statusResponses;
        this.accounts = accounts;
        this.instance = instance;
        this.json = json;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_admin:read:reports')")
    @Operation(
            operationId = "listAdminReports",
            summary = "List reports",
            description = "Open reports, unless `resolved` is given.",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "admin/reports/#get"),
            parameters = {
                    @Parameter(name = "resolved", description = "Only resolved reports. " + ApiDocs.FLAG),
                    @Parameter(name = "account_id", description = "Only reports filed by this account."),
                    @Parameter(name = "target_account_id", description = "Only reports about this account."),
                    @Parameter(name = "limit", description = "How many to return. Values outside 1 to "
                            + ReportPage.MAX_LIMIT + " are clamped to them.",
                            schema = @Schema(type = "integer", defaultValue = "" + ReportPage.DEFAULT_LIMIT))
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "The reports."),
                    @ApiResponse(responseCode = "422", description = "An account ID or a cursor isn't valid.")
            })
    public ResponseEntity<List<AdminReportResponse>> list(
            @RequestParam(required = false) String resolved,
            @RequestParam(name = "account_id", required = false) String accountId,
            @RequestParam(name = "target_account_id", required = false) String targetAccountId,
            @RequestParam(required = false) String limit,
            @RequestParam(name = "max_id", required = false) String maxId,
            @RequestParam(name = "since_id", required = false) String sinceId,
            @RequestParam(name = "min_id", required = false) String minId) {
        var page = RequestParameters.reportPage(
                RequestParameters.flag(resolved), accountId, targetAccountId, limit, maxId, sinceId, minId);
        var found = reports.list(page);
        var filters = new LinkedHashMap<String, String>();
        if (page.resolved()) {
            filters.put("resolved", "true");
        }
        if (page.reporterId() != null) {
            filters.put("account_id", page.reporterId().toString());
        }
        if (page.targetId() != null) {
            filters.put("target_account_id", page.targetId().toString());
        }
        var response = ResponseEntity.ok();
        PageLinks.addTo(response, instance, PATH, filters, page.limit(), found.stream().map(Report::id).toList());
        return response.body(responses(found));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_admin:read:reports')")
    @Operation(
            operationId = "getAdminReport",
            summary = "View a report",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "admin/reports/#get-one"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The report."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND)
            })
    public AdminReportResponse get(@Parameter(description = ApiDocs.ID) @PathVariable String id) {
        return response(reports.find(parse(id)));
    }

    @PutMapping(path = "/{id}", consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.MULTIPART_FORM_DATA_VALUE})
    @PreAuthorize("hasAuthority('SCOPE_admin:write:reports')")
    public AdminReportResponse updateFromForm(
            @Parameter(description = ApiDocs.ID) @PathVariable String id,
            @RequestParam MultiValueMap<String, String> form) {
        return update(id, ReportParameters.fromForm(form));
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_admin:write:reports')")
    // The API docs combine both mappings into one operation, and this replaces what the other one documents.
    @Operation(
            operationId = "updateAdminReport",
            summary = "Change a report's category",
            description = """
                    Differences from Mastodon: there are no rules, so a `category` of `violation`, or any `rule_ids`, \
                    gets a 422.""",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "admin/reports/#update"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The report."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND),
                    @ApiResponse(responseCode = "413", description = "The body is too large."),
                    @ApiResponse(responseCode = "422", description = "The category or rules aren't allowed.")
            })
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ReportUpdateBody.class)))
    public AdminReportResponse updateFromJson(
            @Parameter(description = ApiDocs.ID) @PathVariable String id, HttpServletRequest request) throws IOException {
        return update(id, json.read(request, MAX_BODY_BYTES));
    }

    @PostMapping("/{id}/assign_to_self")
    @PreAuthorize("hasAuthority('SCOPE_admin:write:reports')")
    @Operation(
            operationId = "assignAdminReportToSelf",
            summary = "Take on a report",
            description = ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "admin/reports/#assign_to_self"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The report."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND)
            })
    public AdminReportResponse assignToSelf(
            @AuthenticationPrincipal TokenPrincipal token, @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var admin = admin(token);
        return response(reports.assign(parse(id), admin));
    }

    @PostMapping("/{id}/unassign")
    @PreAuthorize("hasAuthority('SCOPE_admin:write:reports')")
    @Operation(
            operationId = "unassignAdminReport",
            summary = "Stop handling a report",
            description = "Unassigns whichever admin it was assigned to.",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "admin/reports/#unassign"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The report."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND)
            })
    public AdminReportResponse unassign(@Parameter(description = ApiDocs.ID) @PathVariable String id) {
        return response(reports.unassign(parse(id)));
    }

    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasAuthority('SCOPE_admin:write:reports')")
    @Operation(
            operationId = "resolveAdminReport",
            summary = "Resolve a report",
            description = """
                    Resolving a report doesn't act on the account: suspending it or removing its statuses is done \
                    separately. Resolving one that's resolved already keeps who resolved it first.""",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "admin/reports/#resolve"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The report."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND)
            })
    public AdminReportResponse resolve(
            @AuthenticationPrincipal TokenPrincipal token, @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        return response(reports.resolve(parse(id), token.actorId().orElse(null)));
    }

    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasAuthority('SCOPE_admin:write:reports')")
    @Operation(
            operationId = "reopenAdminReport",
            summary = "Reopen a report",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "admin/reports/#reopen"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The report."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND)
            })
    public AdminReportResponse reopen(@Parameter(description = ApiDocs.ID) @PathVariable String id) {
        return response(reports.reopen(parse(id)));
    }

    private AdminReportResponse update(String id, Map<String, ?> parameters) {
        var reportId = parse(id);
        ReportParameters.requireNoRules(parameters);
        return response(ReportParameters.category(RequestParameters.string(parameters, "category"))
                .map(category -> reports.updateCategory(reportId, category))
                .orElseGet(() -> reports.find(reportId)));
    }

    private static UUID admin(TokenPrincipal token) {
        return token.actorId().orElseThrow(InvalidRequestException::requiresUser);
    }

    private static UUID parse(String id) {
        return RequestParameters.uuid(id).orElseThrow(RecordNotFoundException::new);
    }

    private AdminReportResponse response(Optional<Report> report) {
        return responses(List.of(report.orElseThrow(RecordNotFoundException::new))).getFirst();
    }

    /**
     * Accounts and statuses are loaded together for the whole page. A report's accounts are kept even when they're
     * suspended, which is often why it was resolved.
     */
    private List<AdminReportResponse> responses(List<Report> found) {
        var accountMap = accounts.byId(actors.findByIds(
                found.stream().flatMap(Report::accountIds).distinct().toList()));
        var statusIds = found.stream().flatMap(report -> report.statusIds().stream()).distinct().toList();
        var statusMap = statusResponses.list(statuses.findForReport(statusIds), Optional.empty()).stream()
                .collect(Collectors.toMap(status -> UUID.fromString(status.id()), Function.identity()));
        return found.stream()
                .filter(report -> accountMap.containsKey(report.reporterId()) && accountMap.containsKey(report.targetId()))
                .map(report -> new AdminReportResponse(
                        report.id().toString(),
                        report.isResolved(),
                        report.resolvedAt() == null ? null : ApiTimestamps.format(report.resolvedAt()),
                        report.category().value(),
                        report.comment(),
                        report.forwarded(),
                        ApiTimestamps.format(report.createdAt()),
                        ApiTimestamps.format(report.updatedAt()),
                        accountMap.get(report.reporterId()),
                        accountMap.get(report.targetId()),
                        accountMap.get(report.assignedTo()),
                        accountMap.get(report.resolvedBy()),
                        report.statusIds().stream().map(statusMap::get).filter(Objects::nonNull).toList(),
                        List.of()))
                .toList();
    }

    /**
     * Only documents the body, which is read by hand.
     */
    @Schema(name = "AdminReportUpdate")
    private record ReportUpdateBody(
            @Schema(allowableValues = {"spam", "legal", "other"}) String category) {
    }
}
