package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.CustomEmojis;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Tag(name = "Custom emoji", description = "The server's own custom emoji.",
        externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "custom_emojis/"))
public class CustomEmojisController {

    private final CustomEmojis emojis;
    private final InstanceProperties instance;

    CustomEmojisController(CustomEmojis emojis, InstanceProperties instance) {
        this.emojis = emojis;
        this.instance = instance;
    }

    @GetMapping("/api/v1/custom_emojis")
    @Operation(
            operationId = "listCustomEmojis",
            summary = "View the server's custom emoji",
            description = """
                    Every custom emoji an admin has added, ordered by shortcode, including those hidden from the \
                    picker. No token is required. Differences from Mastodon: there are no disabled emoji, since an \
                    admin removes one instead, and the images are WebP.""",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "custom_emojis/#get"),
            responses = @ApiResponse(responseCode = "200", description = "The server's custom emoji."))
    public List<CustomEmojiResponse> list() {
        return CustomEmojiResponse.of(emojis.listLocal(), instance);
    }
}
