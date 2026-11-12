package dev.biribiri.elysium.activitypub.outbound;

import dev.biribiri.elysium.activitypub.delivery.ActivityDeliveries;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity;
import dev.biribiri.elysium.activitypub.vocab.Flag;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.moderation.ReportFiled;
import dev.biribiri.elysium.moderation.Reports;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * Forwards a report to the server of the remote account it's about, when the reporter asked for that. It goes to the
 * account's own inbox, as on Mastodon, and a report only ever has the account's own statuses, which is all that server can
 * act on. Safe
 * to run again, since the {@code Flag}'s ID comes from the report, and does nothing for a report that's gone.
 */
@Service
public class ReportDeliveries {

    private final Reports reports;
    private final ActorService actors;
    private final StatusService statuses;
    private final ActivityDeliveries deliveries;
    private final InstanceProperties instance;
    private final JsonMapper json;

    ReportDeliveries(Reports reports, ActorService actors, StatusService statuses, ActivityDeliveries deliveries,
                     InstanceProperties instance, JsonMapper json) {
        this.reports = reports;
        this.actors = actors;
        this.statuses = statuses;
        this.deliveries = deliveries;
        this.instance = instance;
        this.json = json;
    }

    @ApplicationModuleListener
    public void on(ReportFiled event) {
        var report = reports.find(event.reportId()).filter(found -> found.forwarded() && !found.isRemote());
        if (report.isEmpty()) {
            return;
        }
        var target = actors.findById(report.get().targetId());
        if (target.isEmpty() || !target.get().isActive() || actors.isLocal(target.get())) {
            return;
        }
        var statusUris = statuses.findForReport(report.get().statusIds()).stream().map(Status::uri).toList();
        var flag = Flag.of(instance.baseUrl() + "/reports/" + report.get().id(), actors.instanceActor().uri(),
                report.get().comment(), target.get().uri(), statusUris);
        deliveries.enqueue(OutboundActivity.asInstance(flag.id(), json.writeValueAsString(flag)),
                List.of(target.get().inboxUri()));
    }
}
