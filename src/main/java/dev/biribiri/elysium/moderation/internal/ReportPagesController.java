package dev.biribiri.elysium.moderation.internal;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.content.ModeratedStatuses;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.moderation.Report;
import dev.biribiri.elysium.moderation.ReportPage;
import dev.biribiri.elysium.moderation.Reports;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The admin page for handling reports. Everything under {@code /admin} is limited to admins and needs a CSRF token, through
 * the security configuration. Reported statuses are shown in full, whoever they were for, since reporting one shares it
 * with admins on purpose.
 */
@Controller
class ReportPagesController {

    private static final String PATH = "/admin/reports";

    private final Reports reports;
    private final ActorService actors;
    private final StatusService statuses;
    private final ModeratedStatuses rows;

    ReportPagesController(Reports reports, ActorService actors, StatusService statuses, ModeratedStatuses rows) {
        this.reports = reports;
        this.actors = actors;
        this.statuses = statuses;
        this.rows = rows;
    }

    @GetMapping(PATH)
    String list(@RequestParam(required = false) String resolved, @RequestParam(name = "max_id", required = false) String maxId,
                Model model) {
        var showResolved = resolved != null;
        var page = new ReportPage(showResolved, null, null, ReportPage.DEFAULT_LIMIT, Ids.parse(maxId).orElse(null), null,
                null);
        var found = reports.list(page);
        model.addAttribute("resolved", showResolved);
        model.addAttribute("open", reports.countOpen());
        model.addAttribute("reports", summaries(found));
        model.addAttribute("older", found.size() == page.limit() ? found.getLast().id() : null);
        return "admin-reports";
    }

    @GetMapping(PATH + "/{id}")
    String show(@PathVariable String id, Model model) {
        var report = reports.find(parse(id)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var summary = summaries(List.of(report)).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("report", summary);
        model.addAttribute("statuses", rows.rows(statuses.findForReport(report.statusIds()), true));
        return "admin-report";
    }

    /**
     * A report that's gone meanwhile, with its account, is left gone.
     */
    @PostMapping(PATH + "/{id}/assign")
    String assign(@PathVariable String id, Authentication authentication) {
        reports.assign(parse(id), self(authentication));
        return "redirect:" + PATH + "/" + id;
    }

    @PostMapping(PATH + "/{id}/unassign")
    String unassign(@PathVariable String id) {
        reports.unassign(parse(id));
        return "redirect:" + PATH + "/" + id;
    }

    @PostMapping(PATH + "/{id}/resolve")
    String resolve(@PathVariable String id, Authentication authentication) {
        reports.resolve(parse(id), self(authentication));
        return "redirect:" + PATH;
    }

    @PostMapping(PATH + "/{id}/reopen")
    String reopen(@PathVariable String id) {
        reports.reopen(parse(id));
        return "redirect:" + PATH + "/" + id;
    }

    private List<Summary> summaries(List<Report> found) {
        var ids = found.stream()
                .flatMap(report -> Stream.of(report.reporterId(), report.targetId(), report.assignedTo(), report.resolvedBy()))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        var accounts = actors.findByIds(ids).stream().collect(Collectors.toMap(Actor::id, Function.identity()));
        return found.stream()
                .filter(report -> accounts.containsKey(report.reporterId()) && accounts.containsKey(report.targetId()))
                .map(report -> Summary.from(report, accounts, this::handle, actors::isLocal))
                .toList();
    }

    private String handle(Actor actor) {
        return actors.isLocal(actor) ? actor.username() : actor.username() + "@" + actor.domain();
    }

    private static UUID parse(String id) {
        return Ids.parse(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /**
     * A logged in user's name is their actor ID.
     */
    private static UUID self(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }

    /**
     * @param reporter     who filed it, or for a report from another server, the server that sent it
     * @param targetSearch what finds the target on the accounts page
     * @param assignedTo   null if nobody is handling it
     * @param resolvedBy   null while it's open, or once that admin's account is gone
     */
    record Summary(UUID id, String createdAt, String reporter, boolean fromOtherServer, String target,
                   String targetSearch, boolean targetLocal, boolean targetSuspended, String category, String comment,
                   boolean forwarded, int statusCount, String assignedTo, boolean resolved, String resolvedBy) {

        static Summary from(Report report, Map<UUID, Actor> accounts, Function<Actor, String> handle,
                            Predicate<Actor> isLocal) {
            var reporter = accounts.get(report.reporterId());
            var target = accounts.get(report.targetId());
            var assigned = report.assignedTo() == null ? null : accounts.get(report.assignedTo());
            var resolver = report.resolvedBy() == null ? null : accounts.get(report.resolvedBy());
            return new Summary(
                    report.id(),
                    report.createdAt().truncatedTo(ChronoUnit.SECONDS).toString(),
                    report.isRemote() ? reporter.domain() : handle.apply(reporter),
                    report.isRemote(),
                    handle.apply(target),
                    target.username(),
                    isLocal.test(target),
                    target.isSuspended(),
                    report.category().value(),
                    report.comment(),
                    report.forwarded(),
                    report.statusIds().size(),
                    assigned == null ? null : handle.apply(assigned),
                    report.isResolved(),
                    resolver == null ? null : handle.apply(resolver));
        }
    }
}
