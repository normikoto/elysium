package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.moderation.DomainBlock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Mastodon's Admin::DomainBlock entity. Every block is a full block, so {@code severity}, {@code reject_media}, and
 * {@code reject_reports} are always the same. The list of blocks is never published, so there's no public comment, and
 * nothing to obfuscate.
 */
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

    static final String SEVERITY = "suspend";

    static DomainBlockResponse from(DomainBlock block) {
        return new DomainBlockResponse(
                block.id().toString(),
                block.domain(),
                digest(block.domain()),
                ApiTimestamps.format(block.createdAt()),
                SEVERITY,
                true,
                true,
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
