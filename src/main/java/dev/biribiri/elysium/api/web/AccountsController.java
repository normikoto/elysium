package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountsController {

    private final ActorService actors;
    private final StatusService statuses;
    private final StatusResponses responses;
    private final InstanceProperties instance;

    AccountsController(
            ActorService actors, StatusService statuses, StatusResponses responses, InstanceProperties instance) {
        this.actors = actors;
        this.statuses = statuses;
        this.responses = responses;
        this.instance = instance;
    }

    @GetMapping("/verify_credentials")
    @PreAuthorize("hasAnyAuthority('SCOPE_profile', 'SCOPE_read:accounts')")
    public ResponseEntity<CredentialAccountResponse> verifyCredentials(@AuthenticationPrincipal TokenPrincipal token) {
        var actorId = token.actorId().orElseThrow(InvalidRequestException::requiresUser);
        var stats = statuses.statsFor(Set.of(actorId)).get(actorId);
        return ResponseEntity.of(actors.findLocalById(actorId)
                .map(actor -> CredentialAccountResponse.from(AccountResponse.from(actor, stats, instance))));
    }

    /**
     * Statuses can't be pinned, tagged, or have media yet, so those filters have nothing to return. There are no boosts
     * either, so {@code exclude_reblogs} has no effect.
     */
    @GetMapping("/{id}/statuses")
    @PreAuthorize("isAnonymous() or hasAuthority('SCOPE_read:statuses')")
    public ResponseEntity<List<StatusResponse>> statuses(
            @AuthenticationPrincipal TokenPrincipal token,
            @PathVariable String id,
            @RequestParam(required = false) String limit,
            @RequestParam(name = "max_id", required = false) String maxId,
            @RequestParam(name = "since_id", required = false) String sinceId,
            @RequestParam(name = "min_id", required = false) String minId,
            @RequestParam(name = "exclude_replies", required = false) String excludeReplies,
            @RequestParam(name = "exclude_reblogs", required = false) String excludeReblogs,
            @RequestParam(name = "only_media", required = false) String onlyMedia,
            @RequestParam(required = false) String pinned,
            @RequestParam(required = false) String tagged) {
        var actorId = RequestParameters.uuid(id)
                .filter(candidate -> actors.findLocalById(candidate).isPresent())
                .orElseThrow(RecordNotFoundException::new);
        var page = RequestParameters.page(limit, maxId, sinceId, minId);
        var withoutReplies = RequestParameters.flag(excludeReplies);
        var nothingToReturn = RequestParameters.flag(onlyMedia) || RequestParameters.flag(pinned)
                || (tagged != null && !tagged.isEmpty());
        List<Status> found = nothingToReturn
                ? List.of()
                : statuses.findByAccount(actorId, RequestParameters.viewer(token), withoutReplies, page);

        var filters = new LinkedHashMap<String, String>();
        if (withoutReplies) {
            filters.put("exclude_replies", "true");
        }
        if (RequestParameters.flag(excludeReblogs)) {
            filters.put("exclude_reblogs", "true");
        }
        return responses.page("/api/v1/accounts/" + actorId + "/statuses", filters, page, found);
    }
}
