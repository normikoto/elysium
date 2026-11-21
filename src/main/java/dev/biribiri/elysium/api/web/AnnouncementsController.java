package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.announcement.Announcement;
import dev.biribiri.elysium.announcement.Announcements;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.content.CustomEmojis;
import dev.biribiri.elysium.content.InvalidReactionException;
import dev.biribiri.elysium.text.Hashtags;
import dev.biribiri.elysium.text.PlainText;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping(AnnouncementsController.PATH)
@Tag(name = "Announcements", description = "Announcements from the server's admins.",
        externalDocs = @ExternalDocumentation(url = AnnouncementsController.DOCS))
public class AnnouncementsController {

    static final String PATH = "/api/v1/announcements";

    static final String DOCS = ApiDocs.MASTODON_DOCS + "announcements/";

    private static final String EMOJI = """
            One Unicode emoji, or the shortcode of one of this server's custom emoji. Escape it as a path segment.""";

    private static final String NOT_FOUND = "Not found, unpublished, or the ID isn't a UUID.";

    private final Announcements announcements;
    private final CustomEmojis emojis;
    private final InstanceProperties instance;

    AnnouncementsController(Announcements announcements, CustomEmojis emojis, InstanceProperties instance) {
        this.announcements = announcements;
        this.emojis = emojis;
        this.instance = instance;
    }

    /**
     * Mastodon asks for no particular scope here, only a user.
     */
    @GetMapping
    @SecurityRequirement(name = "oauth2")
    @Operation(operationId = "listAnnouncements", summary = "View announcements",
            description = """
                    The published announcements, oldest first by when they start, or when they were published if they \
                    have no start. Dismissed ones are included, with `read` true. It needs a token with any scope. \
                    Differences from Mastodon: IDs are UUIDs rather than numbers, and `mentions` and `statuses` are \
                    always empty. Admins write them on the `/admin/announcements` page.\s""" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = DOCS + "#get"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The announcements."),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public List<AnnouncementResponse> list(@AuthenticationPrincipal TokenPrincipal token) {
        var actorId = token.actorId().orElseThrow(InvalidRequestException::requiresUser);
        var published = announcements.listPublished();
        var ids = published.stream().map(Announcement::id).toList();
        var dismissed = announcements.dismissedBy(actorId, ids);
        var reactions = announcements.reactions(ids, actorId);
        return published.stream().map(announcement -> response(announcement, dismissed.contains(announcement.id()),
                reactions.getOrDefault(announcement.id(), List.of()).stream()
                        .map(reaction -> AnnouncementReactionResponse.of(reaction, instance)).toList())).toList();
    }

    @PostMapping("/{id}/dismiss")
    @PreAuthorize("hasAuthority('SCOPE_write:accounts')")
    @Operation(operationId = "dismissAnnouncement", summary = "Dismiss an announcement",
            description = "Marks it as read for the user. Doing it again has no more effect. " + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = DOCS + "#dismiss"),
            parameters = @Parameter(name = "id", description = ApiDocs.ID),
            responses = {
                    @ApiResponse(responseCode = "200", description = "An empty object."),
                    @ApiResponse(responseCode = "404", description = NOT_FOUND),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public Map<String, Object> dismiss(@AuthenticationPrincipal TokenPrincipal token, @PathVariable String id) {
        var actorId = token.actorId().orElseThrow(InvalidRequestException::requiresUser);
        var announcementId = RequestParameters.uuid(id).orElseThrow(RecordNotFoundException::new);
        if (!announcements.dismiss(announcementId, actorId)) {
            throw new RecordNotFoundException();
        }
        return Map.of();
    }

    @PutMapping("/{id}/reactions/{name}")
    @PreAuthorize("hasAuthority('SCOPE_write:favourites')")
    @Operation(operationId = "reactToAnnouncement", summary = "React to an announcement with an emoji",
            description = """
                    Adds the user's reaction. Doing it again has no more effect. An announcement can be reacted with \
                    at most\s""" + Announcements.MAX_REACTIONS + """
                     different emoji, by everyone together, as on Mastodon.\s""" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = DOCS + "#put-reactions"),
            parameters = {
                    @Parameter(name = "id", description = ApiDocs.ID),
                    @Parameter(name = "name", description = EMOJI)
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "An empty object."),
                    @ApiResponse(responseCode = "404", description = NOT_FOUND),
                    @ApiResponse(responseCode = "422", description = """
                            It isn't an emoji that can be used, the announcement has been reacted with as many emoji \
                            as it can, or the token has no user.""")
            })
    public Map<String, Object> react(@AuthenticationPrincipal TokenPrincipal token, @PathVariable String id,
                                     @PathVariable String name) {
        return change(token, id, name, true);
    }

    @DeleteMapping("/{id}/reactions/{name}")
    @PreAuthorize("hasAuthority('SCOPE_write:favourites')")
    @Operation(operationId = "unreactToAnnouncement", summary = "Remove a reaction to an announcement",
            description = """
                    Removes the user's reaction with an emoji. Differences from Mastodon: doing it again, or for an \
                    emoji the user didn't react with, succeeds with no more effect, rather than getting a 404.\s"""
                    + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = DOCS + "#delete-reactions"),
            parameters = {
                    @Parameter(name = "id", description = ApiDocs.ID),
                    @Parameter(name = "name", description = EMOJI)
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "An empty object."),
                    @ApiResponse(responseCode = "404", description = NOT_FOUND),
                    @ApiResponse(responseCode = "422", description = """
                            It isn't an emoji that can be used, or the token has no user.""")
            })
    public Map<String, Object> unreact(@AuthenticationPrincipal TokenPrincipal token, @PathVariable String id,
                                       @PathVariable String name) {
        return change(token, id, name, false);
    }

    private Map<String, Object> change(TokenPrincipal token, String id, String name, boolean react) {
        var actorId = token.actorId().orElseThrow(InvalidRequestException::requiresUser);
        var announcementId = RequestParameters.uuid(id).orElseThrow(RecordNotFoundException::new);
        boolean found;
        try {
            found = react
                    ? announcements.react(announcementId, actorId, name)
                    : announcements.unreact(announcementId, actorId, name);
        } catch (InvalidReactionException e) {
            throw new InvalidRequestException("Validation failed: " + e.getMessage());
        }
        if (!found) {
            throw new RecordNotFoundException();
        }
        return Map.of();
    }

    private AnnouncementResponse response(Announcement announcement, boolean read,
                                          List<AnnouncementReactionResponse> reactions) {
        var text = announcement.text();
        return new AnnouncementResponse(
                announcement.id().toString(),
                PlainText.render(text, mention -> null, instance.baseUrl()),
                format(announcement.startsAt()),
                format(announcement.endsAt()),
                announcement.isPublished(),
                announcement.allDay(),
                format(announcement.publishedAt()),
                format(announcement.updatedAt()),
                read,
                List.of(),
                List.of(),
                Hashtags.names(text).stream().map(tag -> StatusTagResponse.of(tag, instance)).toList(),
                CustomEmojiResponse.of(emojis.findLocalIn(List.of(text)).values(), instance),
                reactions);
    }

    private static String format(Instant time) {
        return time == null ? null : ApiTimestamps.format(time);
    }
}
