package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.QuoteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.Optional;

/**
 * Reads an {@code Accept} or {@code Reject} of a {@code QuoteRequest} that this server sent, as FEP-044f has it. The
 * request is found by its ID, which only this server gives out, and only the quoted author can answer it. An
 * {@code Accept}'s {@code result} is the {@code QuoteAuthorization}, which has to be on the quoted author's server. It
 * isn't fetched: other servers check it themselves, from the quote's {@code Note}.
 */
@Component
class QuoteAnswers {

    private static final Logger log = LoggerFactory.getLogger(QuoteAnswers.class);

    private final QuoteService quotes;
    private final InstanceProperties instance;
    private final JsonMapper json;

    QuoteAnswers(QuoteService quotes, InstanceProperties instance, JsonMapper json) {
        this.quotes = quotes;
        this.instance = instance;
        this.json = json;
    }

    /**
     * @param accepted whether this is an {@code Accept}, rather than a {@code Reject}
     * @return whether it answers one of this server's {@code QuoteRequest}s, so that it's nothing else
     */
    boolean answer(InboundActivity activity, boolean accepted) {
        var document = json.readTree(activity.body());
        var object = document.path("object");
        if (object.isObject() && !ActivityJson.types(object.path("type")).isEmpty()
                && !ActivityJson.types(object.path("type")).contains("QuoteRequest")) {
            return false;
        }
        var request = Optional.ofNullable(ActivityJson.idOf(object)).flatMap(ActivityObject::parseUri)
                .filter(uri -> uri.toString().startsWith(instance.baseUrl() + "/"));
        if (request.isEmpty()) {
            return false;
        }
        var actor = URI.create(activity.actorUri());
        URI approval = null;
        if (accepted) {
            approval = Optional.ofNullable(ActivityJson.idOf(document.path("result"))).flatMap(ActivityObject::parseUri)
                    .filter(uri -> InboundActivity.isStorableId(uri.toString()) && Inbox.isOnActorsServer(uri, actor))
                    .orElse(null);
            if (approval == null) {
                log.debug("Ignoring {} {}, whose result isn't a QuoteAuthorization on its actor's server",
                        activity.type(), activity.id());
                return quotes.isOwnRequest(request.get());
            }
        }
        return quotes.answerRequest(request.get(), actor, approval);
    }
}
