package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.ProfileProperties;
import dev.biribiri.elysium.content.AttachmentProperties;
import dev.biribiri.elysium.content.CustomEmojiProperties;
import dev.biribiri.elysium.content.CustomEmojis;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.unit.DataSize;

import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.pathPattern;

/**
 * The largest multipart request each endpoint takes. Only the endpoints that take larger files than an avatar, a header,
 * and a background are listed, and every other one gets the smallest limit, so that a new upload endpoint can't take files as large
 * as a video until it's listed here.
 */
class UploadLimits {

    /**
     * For the rest of a form, besides its files.
     */
    private static final long FORM = DataSize.ofMegabytes(4).toBytes();

    private static final RequestMatcher MEDIA = new OrRequestMatcher(
            pathPattern(HttpMethod.POST, "/api/v1/media"),
            pathPattern(HttpMethod.POST, "/api/v2/media"));

    private static final RequestMatcher EMOJI_PACK = pathPattern(HttpMethod.POST, CustomEmojis.PACK_IMPORT_PATH);

    private final long largestFile;
    private final long media;
    private final long emojiPack;
    private final long other;

    UploadLimits(ProfileProperties profiles, AttachmentProperties attachments, CustomEmojiProperties emojis) {
        var profileImage = profiles.maxImageSize().toBytes();
        var emojiImage = emojis.maxImageSize().toBytes();
        var pack = emojis.maxPackSize().toBytes();
        var attachment = attachments.largest().toBytes();
        largestFile = Math.max(Math.max(profileImage, attachment), Math.max(emojiImage, pack));
        // An attachment can have a thumbnail, and a profile change an avatar, a header, and a background.
        media = attachment + attachments.maxImageSize().toBytes() + FORM;
        emojiPack = pack + FORM;
        other = Math.max(3 * profileImage, emojiImage) + FORM;
    }

    long limitFor(HttpServletRequest request) {
        if (MEDIA.matches(request)) {
            return media;
        }
        if (EMOJI_PACK.matches(request)) {
            return emojiPack;
        }
        return other;
    }

    long largestFile() {
        return largestFile;
    }

    long largestRequest() {
        return Math.max(Math.max(media, emojiPack), other);
    }
}
