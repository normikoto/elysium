package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.moderation.DomainBlock;
import io.swagger.v3.oas.annotations.media.Schema;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Mastodon's Admin::DomainBlock entity. The list of blocks is never published, so there's no public comment, and nothing
 * to obfuscate.
 */
@Schema(name = "AdminDomainBlock", description = """
        Mastodon's Admin::DomainBlock entity. `severity` is `suspend` or `noop`. A suspension always has `reject_media` \
        and `reject_reports` true, since it rejects everything. A `noop` block's `reject_reports` is false, since a \
        limited server's reports are accepted. `obfuscate` is always false, and `public_comment` is always null.""")
record DomainBlockResponse(
        String id,
        String domain,
        String digest,
        @JsonProperty("created_at") String createdAt,
        String severity,
        @JsonProperty("reject_media") boolean rejectMedia,
        @JsonProperty("reject_reports") boolean rejectReports,
        @JsonProperty("private_comment") String privateComment,
        @JsonProperty("public_comment") String publicComment,
        boolean obfuscate
) {

    static DomainBlockResponse from(DomainBlock block) {
        return new DomainBlockResponse(
                block.id().toString(),
                block.domain(),
                digest(block.domain()),
                ApiTimestamps.format(block.createdAt()),
                block.severity().value(),
                block.rejectsMedia(),
                block.severity() == DomainBlock.Severity.SUSPEND,
                block.privateComment(),
                null,
                false);
    }

    private static String digest(String domain) {
        try {
            var hash = MessageDigest.getInstance("SHA-256").digest(domain.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
