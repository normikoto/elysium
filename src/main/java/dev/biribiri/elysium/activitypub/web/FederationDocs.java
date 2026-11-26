package dev.biribiri.elysium.activitypub.web;

import dev.biribiri.elysium.activitypub.inbound.InboxLimitProperties;
import dev.biribiri.elysium.content.FeaturedTags;
import dev.biribiri.elysium.content.StatusPinService;

import static dev.biribiri.elysium.activitypub.vocab.ActivityStreams.ACTIVITY_JSON;
import static dev.biribiri.elysium.activitypub.vocab.ActivityStreams.ANY_LD_JSON;
import static dev.biribiri.elysium.activitypub.vocab.ActivityStreams.LD_JSON;

/**
 * Text that several federation endpoints' descriptions share in the API documentation. These have to follow
 * {@code FEDERATION.md}.
 */
final class FederationDocs {

    static final String ACTIVITYPUB_TAG = "ActivityPub";

    static final String UNSIGNED = "No signature is required. Elysium ignores any signature supplied.";

    static final String VARIES_BY_SIGNATURE = "Responses include `Vary: Signature`.";

    /**
     * For documents that only need a signature when the admin requires signed fetches.
     */
    static final String SIGNED_FETCH = """
            With authorized fetch on, unsigned requests get 401, and a signature failure gets the same status \
            code as at an inbox. Signed responses with authorized fetch on include `Cache-Control: no-store, private`. \
            With authorized fetch off, Elysium ignores signatures.\s""" + VARIES_BY_SIGNATURE;

    /**
     * For URLs that serve a web page too, without what they do with signatures.
     */
    static final String CONTENT_NEGOTIATION = "Request `" + ACTIVITY_JSON + "` or `" + ANY_LD_JSON + "` in `Accept`. "
            + "Elysium supports `" + LD_JSON + "` and accepts other JSON-LD profiles. " + """
            Requests that accept HTML without asking for ActivityPub use the HTML endpoint, including `*/*` or an \
            absent `Accept` header. Other types get 406.

            Responses allow CORS from any origin and vary by `Accept`.""";

    /**
     * For URLs that serve a web page too, and only need a signature when signed fetches are required.
     */
    static final String NEGOTIATED = CONTENT_NEGOTIATION + "\n\n" + SIGNED_FETCH;

    /**
     * For URLs that only serve an ActivityPub document.
     */
    static final String DOCUMENT = "Serves `" + ACTIVITY_JSON + "` or `" + ANY_LD_JSON + "` with any profile, "
            + "including when `Accept` is `*/*` or omitted. Any origin can read it through CORS. "
            + "Requests that accept only HTML get 406.";

    static final String COLLECTION = """
            An `OrderedCollection` with `totalItems` and a `first` page at `?page=true`. Each `OrderedCollectionPage` \
            includes `partOf`, up to\s""" + ActivityPubController.COLLECTION_PAGE_SIZE + """
            \sactor URIs in `orderedItems` with the newest follow first, and `next` if there are more. It counts and \
            lists only accepted follows of active actors. If the user hides their follows, the collection and each \
            page include only `totalItems`.

            """ + DOCUMENT + "\n\n" + SIGNED_FETCH;

    static final String OUTBOX = """
            An `OrderedCollection` of the user's public and unlisted posts and boosts, with `totalItems` and a `first` page at \
            `?page=true`. Each `OrderedCollectionPage` includes `partOf`, up to\s""" + ActivityPubController.COLLECTION_PAGE_SIZE + """
            \s`orderedItems` with the newest first, and `next` if there may be more. A post is a `Create` with an \
            embedded `Note`, and a boost is an `Announce` of the original status's ID. Followers-only and direct \
            posts and boosts are excluded.

            """ + DOCUMENT + "\n\n" + SIGNED_FETCH;

    static final String FEATURED_TAGS = """
            An `OrderedCollection` with `totalItems` and embedded `Hashtag`s in `orderedItems`, as Mastodon serves it: \
            the user's featured tags, most used first, without paging, out of a maximum of\s""" + FeaturedTags.MAX_PER_ACCOUNT + """
            . Each `Hashtag`'s `name` is the normalized name with its `#`, and its `href` is the tag's page on this \
            server.

            Signers the user blocks, including by domain, get no items.

            """ + DOCUMENT + "\n\n" + """
            With authorized fetch on, unsigned requests get 401, and a signature failure gets the same status \
            code as at an inbox. Signed responses with authorized fetch on include `Cache-Control: no-store, private`. \
            With authorized fetch off, Elysium handles signature failures like unsigned requests.\s""" + VARIES_BY_SIGNATURE;

    static final String FEATURED = """
            An `OrderedCollection` with `totalItems` and embedded `Note`s in `orderedItems`, most recently pinned first. \
            It returns visible pins without paging, out of a maximum of\s""" + StatusPinService.MAX_PER_ACCOUNT + """
            \spins per user. It includes public and unlisted pins by default. A signer who can fetch a pinned \
            followers-only `Note` also gets it here, with `Cache-Control: no-store, private`.

            Signers the user blocks, including by domain, get no items.

            """ + DOCUMENT + "\n\n" + """
            With authorized fetch on, unsigned requests get 401, and a signature failure gets the same status \
            code as at an inbox. Signed responses with authorized fetch on include `Cache-Control: no-store, private`. \
            With authorized fetch off, Elysium handles signature failures like unsigned requests.\s""" + VARIES_BY_SIGNATURE;

    static final String USERNAME = "The username, matched case-insensitively.";

    static final String INBOX = """
            Every inbox uses the activity's addressing to find its recipients. Sign requests using draft-cavage \
            HTTP Signatures or RFC 9421. Elysium reads activities as plain JSON without resolving JSON-LD contexts. \
            A 202 response leaves the outcome of processing unknown.

            Draft-cavage signatures must cover `(request-target)`, `host`, `digest`, and either `date` or `(created)`. \
            Elysium accepts `rsa-sha256` and `hs2019`. Only `hs2019` supports `(created)`.

            Elysium handles `Follow`, `Like`, `Announce`, `EmojiReact`, and `Block` and their `Undo`s. It also handles \
            `QuoteRequest`, `Accept` and `Reject` of a follow or quote request, `Create` of a `Note` or `Question`, \
            or an `Article`, `Page`, `Event`, `Video`, `Audio`, or `Image` shown as a link to it, `Update` of an actor \
            or any of those, and `Delete`, `Flag`, `Move`, and pin `Add` and `Remove`. \
            See `FEDERATION.md` for details, including RFC 9421 signature components.

            A forwarded activity with a valid integrity proof from its `actor` is accepted as delivered. Otherwise \
            Elysium answers 202 and fetches the activity by its `id` if it meets the forwarding rules.

            Each address can start\s""" + AddressFetchBudgets.MAX_STARTED_PER_MINUTE
            + " key fetches a minute, with " + AddressFetchBudgets.MAX_RUNNING + " at once. By default, each server can deliver "
            + InboxLimitProperties.DEFAULT_ACTIVITIES_PER_MINUTE + " activities a minute and "
            + InboxLimitProperties.DEFAULT_MEGABYTES_PER_HOUR + " MiB an hour.";

    private FederationDocs() {
    }
}
