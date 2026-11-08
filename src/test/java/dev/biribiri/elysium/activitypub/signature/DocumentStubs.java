package dev.biribiri.elysium.activitypub.signature;

import tools.jackson.databind.JsonNode;

import java.net.URI;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

/**
 * Stubs for tests outside this package, which can't reach the uncounted fetches that key lookups make.
 */
public final class DocumentStubs {

    private DocumentStubs() {
    }

    /**
     * Makes a spy or mock answer that a document is gone, for key lookups as well as other fetches.
     */
    public static void gone(ActivityDocuments documents, URI url) throws DocumentFetchException {
        doThrow(DocumentFetchException.gone("gone")).when(documents).fetch(url);
        doThrow(DocumentFetchException.gone("gone")).when(documents).fetchUncounted(url);
    }

    /**
     * Makes a spy or mock serve an actor's document for key lookups, as if it had been fetched from its {@code id}.
     */
    public static void actor(ActivityDocuments documents, URI id, JsonNode document) throws DocumentFetchException {
        doReturn(document).when(documents).fetchUncounted(id);
    }
}
