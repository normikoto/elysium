package dev.biribiri.elysium.moderation;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.moderation.internal.ReportRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reports to this server's admins, from local accounts and from other servers. What's reported is shared with admins on
 * purpose, so admins read reported statuses whoever they were for.
 */
@Service
public class Reports {

    public static final int MAX_COMMENT_LENGTH = 1000;
    public static final int MAX_STATUSES = 100;

    private final ReportRepository reports;
    private final ActorService actors;
    private final ApplicationEventPublisher events;

    Reports(ReportRepository reports, ActorService actors, ApplicationEventPublisher events) {
        this.reports = reports;
        this.actors = actors;
        this.events = events;
    }

    /**
     * Files a report from a local account. Checking that the reporter can see each status is up to the caller, since it
     * depends on how the reporter found them.
     *
     * @param statuses the target's statuses that it's about
     * @param comment  plain text, or null or empty for none
     * @param forward  whether to send it on to the target's server, which only happens for a remote target
     * @throws InvalidReportException if it can't be filed as asked
     */
    @Transactional
    public Report file(UUID reporterId, Actor target, List<Status> statuses, String comment, ReportCategory category,
                       boolean forward) {
        if (target.id().equals(reporterId)) {
            throw new InvalidReportException("You can't report yourself.");
        }
        if (!target.isActive()) {
            throw new InvalidReportException("This account can't be reported.");
        }
        if (statuses.size() > MAX_STATUSES) {
            throw new InvalidReportException("A report can't include more than %d statuses.".formatted(MAX_STATUSES));
        }
        if (statuses.stream().anyMatch(status -> !status.actorId().equals(target.id()))) {
            throw new InvalidReportException("A report can only include statuses by the account it's about.");
        }
        var report = newReport(reporterId, target.id(), ids(statuses), cleanComment(comment), category,
                forward && !actors.isLocal(target), null);
        reports.insert(report);
        events.publishEvent(new ReportFiled(report.id()));
        return report;
    }

    /**
     * Stores a report from another server, about a local account. A repeat of one that's been stored is ignored.
     * Another server's comment is cut short rather than refused, since its admins chose what to send and nobody there can
     * be asked to try again, and statuses that aren't the target's are left out.
     *
     * @param reporterId  whoever sent it, usually the other server's instance actor
     * @param statuses    the statuses that it's about
     * @param comment     plain text, or null for none
     * @param activityUri the {@code Flag}'s ID
     * @return the report, or empty if it's been stored already
     */
    @Transactional
    public Optional<Report> receive(UUID reporterId, Actor target, List<Status> statuses, String comment,
                                    String activityUri) {
        var theirs = statuses.stream()
                .filter(status -> status.actorId().equals(target.id()))
                .limit(MAX_STATUSES)
                .toList();
        var report = newReport(reporterId, target.id(), ids(theirs),
                comment == null ? "" : Comments.truncate(comment, MAX_COMMENT_LENGTH), ReportCategory.OTHER, false,
                activityUri);
        if (!reports.insert(report)) {
            return Optional.empty();
        }
        events.publishEvent(new ReportFiled(report.id()));
        return Optional.of(report);
    }

    public Optional<Report> find(UUID id) {
        return reports.find(id);
    }

    public List<Report> findByIds(Collection<UUID> ids) {
        return reports.findByIds(ids);
    }

    public List<Report> list(ReportPage page) {
        return reports.findPage(page);
    }

    public long countOpen() {
        return reports.countOpen();
    }

    /**
     * @return the report, or empty if there's none with this ID
     */
    public Optional<Report> assign(UUID id, UUID adminId) {
        return reports.assign(id, adminId) ? reports.find(id) : Optional.empty();
    }

    public Optional<Report> unassign(UUID id) {
        return reports.unassign(id) ? reports.find(id) : Optional.empty();
    }

    public Optional<Report> resolve(UUID id, UUID adminId) {
        return reports.resolve(id, adminId) ? reports.find(id) : Optional.empty();
    }

    public Optional<Report> reopen(UUID id) {
        return reports.reopen(id) ? reports.find(id) : Optional.empty();
    }

    public Optional<Report> updateCategory(UUID id, ReportCategory category) {
        return reports.updateCategory(id, category) ? reports.find(id) : Optional.empty();
    }

    /**
     * Removes the reports by and about a blocked server's actors.
     */
    public void removeForDomain(String domain) {
        reports.deleteForDomain(domain);
    }

    /**
     * Removes the reports by and about a deleted account.
     */
    public void removeForActor(UUID actorId) {
        reports.deleteForActor(actorId);
    }

    private static List<UUID> ids(List<Status> statuses) {
        return statuses.stream().map(Status::id).distinct().toList();
    }

    private static Report newReport(UUID reporterId, UUID targetId, List<UUID> statusIds, String comment,
                                    ReportCategory category, boolean forwarded, String activityUri) {
        var now = Instant.now();
        return new Report(Ids.timeOrdered(now), reporterId, targetId, comment, category, forwarded, activityUri, null,
                null, now, now, null, statusIds);
    }

    private static String cleanComment(String comment) {
        if (comment == null) {
            return "";
        }
        if (comment.length() > MAX_COMMENT_LENGTH) {
            throw new InvalidReportException(
                    "Comments can't be longer than %d characters.".formatted(MAX_COMMENT_LENGTH));
        }
        if (!Comments.isStorable(comment)) {
            throw new InvalidReportException("Comments can't contain invalid characters.");
        }
        return comment.strip();
    }
}
