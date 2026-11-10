package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.content.AttachmentProperties;
import dev.biribiri.elysium.content.Focus;
import dev.biribiri.elysium.content.InvalidMediaAttachmentException;
import dev.biribiri.elysium.content.MediaAttachments;
import dev.biribiri.elysium.content.MediaUpload;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;

@RestController
@Tag(name = "Media", description = "Uploading images, video, and audio to attach to statuses.",
        externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "media/"))
public class MediaController {

    private static final long UPDATE_BODY_BYTES = 64 * 1024;

    private static final String UPLOAD_DIFFERENCES = """
            Differences from Mastodon:

            - The file is processed before the response, which is always a 200 with its `url`, never a 202.
            - Images are re-encoded as WebP, which removes their metadata, and scaled down to fit 3840 × 2160 pixels in \
            area. An animated GIF becomes an animated WebP, with type `image` rather than `gifv`.
            - Video and audio are kept as they were uploaded, metadata included, and get no preview unless a \
            `thumbnail` is uploaded with them. A `thumbnail` with an image gets a 422.
            - Images, video, and audio each have their own size limit, which `GET /api/v1/instance` gives, with audio's \
            under `video_size_limit` when it's larger.
            - A user can have only so many uploads that aren't attached to a status yet, 20 by default, and one past \
            that gets a 422. Uploads are removed if they aren't attached within a day, by default.
            - `blurhash` is always null.

            """ + ApiDocs.NEEDS_USER;

    private final MediaAttachments attachments;
    private final InstanceProperties instance;
    private final AttachmentProperties properties;
    private final JsonBodies json;

    MediaController(MediaAttachments attachments, InstanceProperties instance, AttachmentProperties properties,
                    JsonBodies json) {
        this.attachments = attachments;
        this.instance = instance;
        this.properties = properties;
        this.json = json;
    }

    @PostMapping(path = "/api/v2/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_write:media')")
    @Operation(
            operationId = "uploadMedia",
            summary = "Upload media to attach to a status",
            description = UPLOAD_DIFFERENCES,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "media/#v2"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The upload, ready to attach."),
                    @ApiResponse(responseCode = "413", description = "The body is too large."),
                    @ApiResponse(responseCode = "422", description = """
                            The file is missing, too large, or not a format that's accepted, a value is invalid, the user \
                            has too many uploads waiting, or the token has no user.""")
            })
    @RequestBody(required = true, content = @Content(
            mediaType = MediaType.MULTIPART_FORM_DATA_VALUE, schema = @Schema(implementation = UploadBody.class)))
    public MediaAttachmentResponse upload(
            @AuthenticationPrincipal TokenPrincipal token,
            @Parameter(hidden = true) @RequestParam(required = false) MultipartFile file,
            @Parameter(hidden = true) @RequestParam(required = false) MultipartFile thumbnail,
            @Parameter(hidden = true) @RequestParam(required = false) String description,
            @Parameter(hidden = true) @RequestParam(required = false) String focus) throws IOException {
        var account = requireUser(token);
        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException("Validation failed: File can't be blank");
        }
        // Before it's read, with the limit for the largest kind, since its kind isn't known until it has been.
        if (file.getSize() > properties.largest().toBytes()) {
            throw new InvalidMediaAttachmentException("The file is larger than any upload can be");
        }
        var parsedFocus = Focus.parse(focus).orElse(null);
        try (InputStream in = file.getInputStream();
             InputStream thumb = thumbnail == null || thumbnail.isEmpty() ? null : thumbnail.getInputStream()) {
            var uploaded = attachments.upload(account, new MediaUpload(in, thumb, description, parsedFocus));
            return MediaAttachmentResponse.from(uploaded, instance);
        }
    }

    @PostMapping(path = "/api/v1/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_write:media')")
    @Operation(
            operationId = "uploadMediaV1",
            summary = "Upload media to attach to a status (deprecated)",
            description = "The same as `POST /api/v2/media`, which Mastodon replaced this with.\n\n" + UPLOAD_DIFFERENCES,
            deprecated = true,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "media/#v1"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The upload, ready to attach."),
                    @ApiResponse(responseCode = "413", description = "The body is too large."),
                    @ApiResponse(responseCode = "422", description = """
                            The file is missing, too large, or not a format that's accepted, a value is invalid, the user \
                            has too many uploads waiting, or the token has no user.""")
            })
    @RequestBody(required = true, content = @Content(
            mediaType = MediaType.MULTIPART_FORM_DATA_VALUE, schema = @Schema(implementation = UploadBody.class)))
    public MediaAttachmentResponse uploadV1(
            @AuthenticationPrincipal TokenPrincipal token,
            @Parameter(hidden = true) @RequestParam(required = false) MultipartFile file,
            @Parameter(hidden = true) @RequestParam(required = false) MultipartFile thumbnail,
            @Parameter(hidden = true) @RequestParam(required = false) String description,
            @Parameter(hidden = true) @RequestParam(required = false) String focus) throws IOException {
        return upload(token, file, thumbnail, description, focus);
    }

    @GetMapping("/api/v1/media/{id}")
    @PreAuthorize("hasAuthority('SCOPE_write:media')")
    @Operation(
            operationId = "getMedia",
            summary = "View an upload",
            description = """
                    Only finds the user's own uploads that aren't attached to a status yet, as on Mastodon. Uploads are \
                    processed before they're returned, so this never gets a 206. """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "media/#get"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The upload."),
                    @ApiResponse(responseCode = "404", description = """
                            Not found, the ID isn't a UUID, the upload isn't the user's, or it's attached to a status."""),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public MediaAttachmentResponse get(
            @AuthenticationPrincipal TokenPrincipal token, @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var account = requireUser(token);
        return RequestParameters.uuid(id)
                .flatMap(mediaId -> attachments.findUnattached(mediaId, account))
                .map(found -> MediaAttachmentResponse.from(found, instance))
                .orElseThrow(RecordNotFoundException::new);
    }

    @PutMapping(path = "/api/v1/media/{id}",
            consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.MULTIPART_FORM_DATA_VALUE})
    @PreAuthorize("hasAuthority('SCOPE_write:media')")
    public MediaAttachmentResponse updateFromForm(
            @AuthenticationPrincipal TokenPrincipal token, @PathVariable String id,
            @RequestParam MultiValueMap<String, String> form,
            @RequestParam(required = false) MultipartFile thumbnail) {
        if (thumbnail != null && !thumbnail.isEmpty()) {
            throw new InvalidRequestException("Validation failed: thumbnail can only be uploaded with the file");
        }
        return update(token, id, form.toSingleValueMap());
    }

    @PutMapping(path = "/api/v1/media/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_write:media')")
    // The API docs combine both mappings into one operation, and this replaces what the other one documents.
    @Operation(
            operationId = "updateMedia",
            summary = "Change an upload's description or focus",
            description = """
                    Only the user's own uploads that aren't attached to a status yet can be changed, as on Mastodon. \
                    Once one is, it's changed by editing the status, with `media_attributes[]`.

                    Differences from Mastodon: a new `thumbnail` gets a 422. A missing `description` or `focus` keeps \
                    what the upload has, and an empty `description` removes it.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "media/#update"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The upload as it is now."),
                    @ApiResponse(responseCode = "404", description = """
                            Not found, the ID isn't a UUID, the upload isn't the user's, or it's attached to a status."""),
                    @ApiResponse(responseCode = "413", description = "The body is too large."),
                    @ApiResponse(responseCode = "422", description = """
                            A value is invalid or of the wrong type, or the token has no user.""")
            })
    @RequestBody(required = true, content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = UpdateBody.class)))
    public MediaAttachmentResponse updateFromJson(
            @AuthenticationPrincipal TokenPrincipal token, @Parameter(description = ApiDocs.ID) @PathVariable String id,
            HttpServletRequest request) throws IOException {
        return update(token, id, json.read(request, UPDATE_BODY_BYTES));
    }

    @DeleteMapping("/api/v1/media/{id}")
    @PreAuthorize("hasAuthority('SCOPE_write:media')")
    @Operation(
            operationId = "deleteMedia",
            summary = "Delete an upload",
            description = """
                    Deletes one of the user's uploads that isn't attached to a status, and its files. \
                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "media/#delete"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "An empty object."),
                    @ApiResponse(responseCode = "404", description = """
                            Not found, the ID isn't a UUID, or the upload isn't the user's."""),
                    @ApiResponse(responseCode = "422", description = """
                            The upload is attached to a status, or the token has no user.""")
            })
    public Map<String, Object> delete(
            @AuthenticationPrincipal TokenPrincipal token, @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var account = requireUser(token);
        var mediaId = RequestParameters.uuid(id).orElseThrow(RecordNotFoundException::new);
        return switch (attachments.deleteUnattached(mediaId, account)) {
            case DELETED -> Map.of();
            case ATTACHED -> throw new InvalidRequestException("Media attachment is currently used by a status");
            case NOT_FOUND -> throw new RecordNotFoundException();
        };
    }

    private MediaAttachmentResponse update(TokenPrincipal token, String id, Map<String, ?> parameters) {
        var account = requireUser(token);
        var mediaId = RequestParameters.uuid(id).orElseThrow(RecordNotFoundException::new);
        // Unlike other strings, an empty description is a value, which removes the one there is.
        var description = switch (parameters.get("description")) {
            case null -> null;
            case String string -> string;
            default -> throw new InvalidRequestException("Validation failed: description must be a string");
        };
        var focus = Focus.parse(RequestParameters.string(parameters, "focus")).orElse(null);
        return attachments.updateUnattached(mediaId, account, description, focus)
                .map(found -> MediaAttachmentResponse.from(found, instance))
                .orElseThrow(RecordNotFoundException::new);
    }

    private static UUID requireUser(TokenPrincipal token) {
        return token.actorId().orElseThrow(InvalidRequestException::requiresUser);
    }

    /**
     * Only documents the body, which is read by Spring.
     */
    @Schema(name = "MediaUpload")
    private record UploadBody(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "binary", description = """
                    A PNG, JPEG, GIF or WebP image, an MP4, MOV or WebM video, or MP3, M4A, Ogg, FLAC or WAV audio. Its \
                    type is worked out from its content, not from what it's sent as.""")
            String file,
            @Schema(format = "binary", description = "An image to show for a video or audio.")
            String thumbnail,
            @Schema(description = "What it shows, for people who can't see it, up to "
                    + MediaAttachments.MAX_DESCRIPTION_LENGTH + " characters.")
            String description,
            @Schema(description = "Where to keep in view when it's cropped, as x,y from -1 to 1.")
            String focus) {
    }

    /**
     * Only documents the body, which is read by hand.
     */
    @Schema(name = "MediaUpdate")
    private record UpdateBody(
            @Schema(description = "What it shows, for people who can't see it. Empty removes it, and missing keeps it.")
            String description,
            @Schema(description = "Where to keep in view when it's cropped, as x,y from -1 to 1. Missing keeps it.")
            String focus) {
    }
}
