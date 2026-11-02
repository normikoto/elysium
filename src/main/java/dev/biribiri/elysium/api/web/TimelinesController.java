package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.timeline.TimelineService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;

@RestController
@RequestMapping("/api/v1/timelines")
public class TimelinesController {

    private static final String PUBLIC_PATH = "/api/v1/timelines/public";

    private final TimelineService timelines;
    private final StatusResponses responses;

    TimelinesController(TimelineService timelines, StatusResponses responses) {
        this.timelines = timelines;
        this.responses = responses;
    }

    /**
     * Every status is local and none have media yet, so {@code local} has no effect, and {@code remote} and
     * {@code only_media} have nothing to return.
     */
    @GetMapping("/public")
    @PreAuthorize("isAnonymous() or hasAuthority('SCOPE_read:statuses')")
    public ResponseEntity<List<StatusResponse>> publicTimeline(
            @RequestParam(required = false) String limit,
            @RequestParam(name = "max_id", required = false) String maxId,
            @RequestParam(name = "since_id", required = false) String sinceId,
            @RequestParam(name = "min_id", required = false) String minId,
            @RequestParam(required = false) String local,
            @RequestParam(required = false) String remote,
            @RequestParam(name = "only_media", required = false) String onlyMedia) {
        var page = RequestParameters.page(limit, maxId, sinceId, minId);
        var nothingToReturn = RequestParameters.flag(remote) || RequestParameters.flag(onlyMedia);
        List<Status> found = nothingToReturn ? List.of() : timelines.publicTimeline(page);

        var filters = new LinkedHashMap<String, String>();
        if (RequestParameters.flag(local)) {
            filters.put("local", "true");
        }
        return responses.page(PUBLIC_PATH, filters, page, found);
    }
}
