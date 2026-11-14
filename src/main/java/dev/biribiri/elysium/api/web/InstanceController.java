package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.ProfileService;
import dev.biribiri.elysium.actor.RegistrationProperties;
import dev.biribiri.elysium.content.AttachmentProperties;
import dev.biribiri.elysium.content.MediaAttachments;
import dev.biribiri.elysium.content.Poll;
import dev.biribiri.elysium.content.StatusPinService;
import dev.biribiri.elysium.content.StatusProperties;
import dev.biribiri.elysium.content.StatusService;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@Tag(name = "Instance", description = "Information about the server.",
        externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "instance/"))
public class InstanceController {

    /**
     * Clients parse this to detect features, so it has to start with a Mastodon version number.
     */
    private static final String VERSION = "4.0.0 (compatible; Elysium)";

    private static final int VIDEO_FRAME_RATE_LIMIT = 120;

    /**
     * 3840 × 2160.
     */
    private static final long VIDEO_MATRIX_LIMIT = 8_294_400;

    private final InstanceProperties instance;
    private final StatusProperties statusProperties;
    private final ActorService actors;
    private final StatusService statuses;
    private final RegistrationProperties registration;
    private final ProfileService profiles;
    private final AttachmentProperties attachments;

    InstanceController(
            InstanceProperties instance,
            StatusProperties statusProperties,
            ActorService actors,
            StatusService statuses,
            RegistrationProperties registration,
            ProfileService profiles,
            AttachmentProperties attachments) {
        this.attachments = attachments;
        this.profiles = profiles;
        this.instance = instance;
        this.statusProperties = statusProperties;
        this.actors = actors;
        this.statuses = statuses;
        this.registration = registration;
    }

    @GetMapping("/v1/instance")
    @Operation(
            operationId = "getInstanceV1",
            summary = "View information about the server",
            description = """
                    Differences from Mastodon: the status length limit is in `max_toot_chars` and \
                    `configuration.statuses.max_characters`, and defaults to\s""" + StatusProperties.DEFAULT_MAX_CHARACTERS
                    + """
                     rather than 500. `version` is a Mastodon version followed by `(compatible; Elysium)`, for \
                    clients that check it to decide what to use. The streaming API isn't implemented, though \
                    `urls.streaming_api` is set. `configuration.accounts.max_profile_fields` says how many fields a \
                    profile can have, which Mastodon doesn't send.""",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "instance/#v1"),
            responses = @ApiResponse(responseCode = "200", description = "Information about the server."))
    public InstanceResponse instance() {
        var maxCharacters = statusProperties.maxCharacters();
        var domain = instance.domain();
        return new InstanceResponse(
                domain,
                domain,
                "",
                "",
                "",
                VERSION,
                new InstanceResponse.Urls(instance.baseUrl().toString().replaceFirst("^http", "ws")),
                new InstanceResponse.Stats(actors.countLocal(), statuses.countLocal(), 0),
                null,
                List.of(),
                registration.acceptsUninvited(),
                registration.approvalRequired(),
                // Mastodon means whether users can make invites, which only admins can, from the command line.
                false,
                maxCharacters,
                new InstanceResponse.Configuration(
                        new InstanceResponse.Accounts(0, StatusPinService.MAX_PER_ACCOUNT, profiles.maxFields()),
                        new InstanceResponse.Statuses(
                                maxCharacters, MediaAttachments.MAX_PER_STATUS, statuses.charactersReservedPerUrl()),
                        mediaAttachments(),
                        new InstanceResponse.Polls(Poll.MAX_OPTIONS, Poll.MAX_OPTION_CHARACTERS, Poll.MIN_EXPIRATION,
                                Poll.MAX_EXPIRATION)),
                null,
                List.of());
    }

    /**
     * Mastodon's API version 7 is the one with quotes, which clients only offer once this says so.
     */
    static final int MASTODON_API_VERSION = 7;

    @GetMapping("/v2/instance")
    @Operation(
            operationId = "getInstanceV2",
            summary = "View information about the server",
            description = """
                    Differences from Mastodon: the status length limit is in `configuration.statuses.max_characters`, \
                    and defaults to\s""" + StatusProperties.DEFAULT_MAX_CHARACTERS + """
                     rather than 500. `api_versions.mastodon` is\s""" + MASTODON_API_VERSION + """
                    , which is when Mastodon added quotes, though some of what Mastodon added in the versions before it \
                    isn't here, as `docs/api.md` lists. There's no thumbnail, icon, contact account, or rules, and \
                    `usage.users.active_month` counts every local account. The streaming API and push notifications \
                    aren't implemented, though `configuration.urls.streaming` is set.""",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "instance/#v2"),
            responses = @ApiResponse(responseCode = "200", description = "Information about the server."))
    public InstanceV2Response instanceV2() {
        var maxCharacters = statusProperties.maxCharacters();
        var domain = instance.domain();
        return new InstanceV2Response(
                domain,
                domain,
                VERSION,
                "https://github.com/normikoto/elysium",
                "",
                new InstanceV2Response.Usage(new InstanceV2Response.Users(actors.countLocal())),
                null,
                List.of(),
                List.of(),
                new InstanceV2Response.Configuration(
                        Map.of("streaming", instance.baseUrl().toString().replaceFirst("^http", "ws")),
                        new InstanceResponse.Accounts(0, StatusPinService.MAX_PER_ACCOUNT, profiles.maxFields()),
                        new InstanceResponse.Statuses(
                                maxCharacters, MediaAttachments.MAX_PER_STATUS, statuses.charactersReservedPerUrl()),
                        mediaAttachments(),
                        new InstanceResponse.Polls(Poll.MAX_OPTIONS, Poll.MAX_OPTION_CHARACTERS, Poll.MIN_EXPIRATION,
                                Poll.MAX_EXPIRATION),
                        Map.of("enabled", false)),
                new InstanceV2Response.Registrations(
                        registration.acceptsUninvited(), registration.approvalRequired(), null),
                Map.of("mastodon", MASTODON_API_VERSION),
                new InstanceV2Response.Contact("", null),
                List.of());
    }

    /**
     * Mastodon has one limit for video and audio, so it's the larger of them. Video isn't decoded, so its frame rate and
     * size aren't limited, and these are what clients are told to keep within.
     */
    private InstanceResponse.MediaAttachments mediaAttachments() {
        return new InstanceResponse.MediaAttachments(
                MediaAttachments.SUPPORTED_TYPES,
                attachments.maxImageSize().toBytes(),
                MediaAttachments.MAX_IMAGE_PIXELS,
                Math.max(attachments.maxVideoSize().toBytes(), attachments.maxAudioSize().toBytes()),
                VIDEO_FRAME_RATE_LIMIT,
                VIDEO_MATRIX_LIMIT);
    }
}
