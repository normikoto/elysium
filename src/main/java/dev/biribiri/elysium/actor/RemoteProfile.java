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
 * @param discoverable whether it's listed in directories, as its {@code discoverable} says
 * @param cat       Misskey's {@code isCat}
 * @param published when its server says it was created, or null if it doesn't
 * @param url       its profile page, or null if the document doesn't give one
 * @param alsoKnownAs the accounts it says are also it
 * @param movedTo   the account it says it has moved to, or null
 */
public record RemoteProfile(
        String displayName,
        String note,
        String noteHtml,
        URI avatarUrl,
        URI headerUrl,
        List<Field> fields,
        boolean manuallyApprovesFollowers,
        boolean indexable,
        boolean discoverable,
        boolean bot,
        boolean cat,
        boolean group,
        Instant published,
        URI url,
        List<URI> alsoKnownAs,
        URI movedTo) {

    public static final RemoteProfile EMPTY =
            new RemoteProfile("", "", "", null, null, List.of(), false, false, false, false, false, false, null, null);

    /**
     * @param value     as text
     * @param valueHtml as sanitized HTML
     */
    public record Field(String name, String value, String valueHtml) {
    }

    public RemoteProfile {
        fields = List.copyOf(fields);
        alsoKnownAs = List.copyOf(alsoKnownAs);
    }

    /**
     * One that doesn't say it's moved, or is also another account.
     */
    public RemoteProfile(
            String displayName,
            String note,
            String noteHtml,
            URI avatarUrl,
            URI headerUrl,
            List<Field> fields,
            boolean manuallyApprovesFollowers,
            boolean indexable,
            boolean discoverable,
            boolean bot,
            boolean cat,
            boolean group,
            Instant published,
            URI url) {
        this(displayName, note, noteHtml, avatarUrl, headerUrl, fields, manuallyApprovesFollowers, indexable,
                discoverable, bot, cat, group, published, url, List.of(), null);
    }
}
