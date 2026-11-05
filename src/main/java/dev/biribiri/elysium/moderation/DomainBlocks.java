package dev.biribiri.elysium.moderation;

import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.moderation.internal.DomainBlockRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The servers this one has cut off. Each check goes to the database rather than a cache, so that a block applies straight
 * away on every node. This is one indexed lookup, and requests that it's checked for already do far more work.
 */
@Service
public class DomainBlocks {

    public static final int MAX_COMMENT_LENGTH = 1000;

    private final DomainBlockRepository blocks;
    private final InstanceProperties instance;
    private final ApplicationEventPublisher events;

    DomainBlocks(DomainBlockRepository blocks, InstanceProperties instance, ApplicationEventPublisher events) {
        this.blocks = blocks;
        this.instance = instance;
        this.events = events;
    }

    /**
     * @param domain  the domain to block, along with its subdomains
     * @param comment a note for admins, or null or empty for none
     * @throws InvalidDomainBlockException  if this domain can't be blocked
     * @throws DomainBlockConflictException if it's already blocked
     */
    @Transactional
    public DomainBlock create(String domain, String comment) {
        var name = DomainName.parse(domain);
        var ownHost = instance.baseUrl().getHost();
        if (ownHost.equals(name) || ownHost.endsWith("." + name)) {
            throw new InvalidDomainBlockException("This server can't block itself.");
        }
        var covering = blocks.findBroadest(DomainName.suffixes(name));
        if (covering.isPresent()) {
            throw new DomainBlockConflictException(covering.get());
        }
        var now = Instant.now();
        var block = new DomainBlock(Ids.timeOrdered(now), name, cleanComment(comment), now);
        if (!blocks.insert(block)) {
            // Another request got there since the check above, and has committed by now.
            throw new DomainBlockConflictException(blocks.findBroadest(List.of(name)).orElse(block));
        }
        events.publishEvent(new DomainBlocked(block.id()));
        return block;
    }

    /**
     * @param comment a note for admins, or null or empty for none
     * @return the block, if there is one with this ID
     */
    public Optional<DomainBlock> updateComment(UUID id, String comment) {
        return blocks.updateComment(id, cleanComment(comment));
    }

    /**
     * Stops blocking a domain. Nothing that was removed comes back, but its server can start again, such as by asking to
     * follow again.
     *
     * @return whether there was a block with this ID
     */
    public boolean delete(UUID id) {
        return blocks.delete(id);
    }

    public Optional<DomainBlock> find(UUID id) {
        return blocks.find(id);
    }

    public List<DomainBlock> list(DomainBlockPage page) {
        return blocks.findPage(page);
    }

    /**
     * @param host a host from another server, with or without a port. A host that can't be read isn't blocked, so callers
     *             still have to deal with it the way they would anyway.
     * @return whether this host is covered by a block
     */
    public boolean isBlocked(String host) {
        return !blockedHosts(Collections.singleton(host)).isEmpty();
    }

    /**
     * Like {@link #isBlocked}, with a single query for all of them.
     *
     * @return those hosts that are covered by a block, each as it was given here
     */
    public Set<String> blockedHosts(Collection<String> hosts) {
        var suffixes = new HashMap<String, List<String>>();
        for (var host : hosts) {
            DomainName.normalizeHost(host).ifPresent(name -> suffixes.put(host, DomainName.suffixes(name)));
        }
        var all = suffixes.values().stream().flatMap(List::stream).collect(Collectors.toSet());
        if (all.isEmpty()) {
            return Set.of();
        }
        var blocked = blocks.findBlocked(all);
        return suffixes.entrySet().stream()
                .filter(entry -> entry.getValue().stream().anyMatch(blocked::contains))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    private static String cleanComment(String comment) {
        if (comment == null || comment.isBlank()) {
            return null;
        }
        if (comment.length() > MAX_COMMENT_LENGTH) {
            throw new InvalidDomainBlockException(
                    "Comments can't be longer than %d characters.".formatted(MAX_COMMENT_LENGTH));
        }
        return comment.strip();
    }
}
