package dev.biribiri.elysium.actor;

import java.net.URI;
import java.time.Instant;
import java.util.List;

/**
 * What an actor document from another server says about the account, as it's stored. The HTML has been sanitized, and the
 * text has been taken from it.
 *
 * @param note      the bio as text
 * @param noteHtml  the bio as sanitized HTML, which is empty if there's none
 * @param avatarUrl an HTTPS URL, or null if there isn't one
 * @param headerUrl an HTTPS URL, or null if there isn't one
 * @param published when its server says it was created, or null if it doesn't
 * @param url       its profile page, or null if the document doesn't give one
 */
public record RemoteProfile(
        String displayName,
        String note,
        String noteHtml,
        URI avatarUrl,
        URI headerUrl,
        List<Field> fields,
        boolean manuallyApprovesFollowers,
        boolean bot,
        boolean group,
        Instant published,
        URI url) {

    public static final RemoteProfile EMPTY =
            new RemoteProfile("", "", "", null, null, List.of(), false, false, false, null, null);

    /**
     * @param value     as text
     * @param valueHtml as sanitized HTML
     */
    public record Field(String name, String value, String valueHtml) {
    }

    public RemoteProfile {
        fields = List.copyOf(fields);
    }
}
