package dev.biribiri.elysium.activitypub.web;

import dev.biribiri.elysium.activitypub.inbound.InboxLimitProperties;

import static dev.biribiri.elysium.activitypub.vocab.ActivityStreams.ACTIVITY_JSON;
import static dev.biribiri.elysium.activitypub.vocab.ActivityStreams.ANY_LD_JSON;
import static dev.biribiri.elysium.activitypub.vocab.ActivityStreams.LD_JSON;

/**
 * Text that several federation endpoints' descriptions share in the API documentation. These have to follow
 * {@code FEDERATION.md}.
 */
final class FederationDocs {

    static final String ACTIVITYPUB_TAG = "ActivityPub";

    static final String UNSIGNED = "Doesn't need a signature, and any signature on the request is ignored.";

    /**
     * For URLs that serve a web page too.
     */
    static final String NEGOTIATED = "Served as `" + ACTIVITY_JSON + "` or `" + LD_JSON + "`, to a request that asks "
            + "for one of these in its `Accept` header, and `" + ANY_LD_JSON + "` is accepted with any profile. " + """
            A request that accepts HTML instead, including `*/*` or no header, gets the web page at the same URL, and \
            others get a 406. Any origin can read both through CORS, and the response varies by `Accept`.\s""" + UNSIGNED;

    /**
     * For URLs that only serve an ActivityPub document.
     */
    static final String DOCUMENT = "Served as `" + ACTIVITY_JSON + "` or `" + ANY_LD_JSON + "` with any profile, " + """
            including for `*/*` or no `Accept` header, and any origin can read it through CORS. There's no web page at \
            this URL, so a request that only accepts HTML gets a 406.""";

    static final String COLLECTION = """
            An `OrderedCollection` with `totalItems` and a `first` page, at `?page=true`. A page is an \
            `OrderedCollectionPage` with `partOf`, up to\s""" + ActivityPubController.COLLECTION_PAGE_SIZE + """
             `orderedItems`, which are actor URIs, newest follow first, and a `next` page if there are more. Only \
            accepted follows are counted or listed, and suspended and deleted actors are left out. If the user hides \
            who's in their collections, the collection and every page only have `totalItems`.\s"""
            + DOCUMENT + " " + UNSIGNED;

    static final String USERNAME = "The username, matched case-insensitively.";

    static final String INBOX = """
            Who an activity is for comes from the activity rather than the inbox it was sent to, so every inbox works \
            the same way. The request has to be signed with draft-cavage HTTP Signatures, covering `(request-target)`, \
            `host`, either `date` or `(created)`, and `digest`, with `rsa-sha256` or `hs2019`. Activities are read as \
            plain JSON, without a JSON-LD processor, and are processed later, so the answer doesn't say whether one \
            worked. Only `Follow`, `Undo` of a `Follow`, and `Accept` and `Reject` of a `Follow` are acted on.

            An activity signed by someone other than its `actor` is answered with a 202 and fetched from its `id` \
            later. Each address can start\s""" + AddressFetchBudgets.MAX_STARTED_PER_MINUTE
            + " key fetches a minute, with " + AddressFetchBudgets.MAX_RUNNING + " at once, and each server can deliver "
            + InboxLimitProperties.DEFAULT_ACTIVITIES_PER_MINUTE + " activities a minute and "
            + InboxLimitProperties.DEFAULT_MEGABYTES_PER_HOUR + " MiB an hour by default.";

    private FederationDocs() {
    }
}
