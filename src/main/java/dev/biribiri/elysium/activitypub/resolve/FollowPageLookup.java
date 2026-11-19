package dev.biribiri.elysium.activitypub.resolve;

import dev.biribiri.elysium.activitypub.vocab.JsonResourceDescriptor;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.RemoteFollowPages;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Reads a follow page's template from an account's WebFinger answer. FEP-3b86's {@code Follow} intent is preferred, as the
 * newer and more specific of the two, and the OStatus subscribe link is what Mastodon, Akkoma, and Misskey give.
 *
 * <p>Only an {@code https} page is accepted, so a server can't send the visitor's browser to a script or a plain-HTTP page.
 * It can still be on another host than the address, as when Mastodon's web domain isn't the one in its addresses.
 */
@Component
class FollowPageLookup implements RemoteFollowPages {

    private static final List<Template> TEMPLATES = List.of(
            new Template(JsonResourceDescriptor.FOLLOW_INTENT, "{object}"),
            new Template(JsonResourceDescriptor.OSTATUS_SUBSCRIBE, "{uri}"));

    private final WebFingerRequests webFinger;
    private final DomainBlocks blocks;
    private final InstanceProperties instance;

    FollowPageLookup(WebFingerRequests webFinger, DomainBlocks blocks, InstanceProperties instance) {
        this.webFinger = webFinger;
        this.blocks = blocks;
        this.instance = instance;
    }

    @Override
    public Optional<URI> find(String username, String domain, URI target) {
        return RemoteActorLookup.remoteDomain(username, domain, instance, blocks)
                .flatMap(normalized -> webFinger.lookUp(username, normalized))
                .flatMap(descriptor -> page(descriptor, target));
    }

    private static Optional<URI> page(JsonNode descriptor, URI target) {
        var encoded = URLEncoder.encode(target.toString(), StandardCharsets.UTF_8);
        for (var template : TEMPLATES) {
            var found = descriptor.path("links").valueStream()
                    .filter(link -> template.rel().equals(link.path("rel").stringValue(null)))
                    .map(link -> link.path("template").stringValue(null))
                    .filter(value -> value != null && value.contains(template.placeholder()))
                    .flatMap(value -> RemoteActorLookup.parse(value.replace(template.placeholder(), encoded)).stream())
                    .findFirst();
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private record Template(String rel, String placeholder) {
    }
}
