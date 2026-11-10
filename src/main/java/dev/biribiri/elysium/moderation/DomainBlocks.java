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
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The servers this one has cut off or limited. A block is either a suspension, which cuts a server off, or a {@code noop}
 * one that only limits it, such as by rejecting its attachments. Each check goes to the database rather than a cache, so that a block applies straight
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
        return create(domain, DomainBlock.Severity.SUSPEND, false, comment);
    }

    /**
     * @param domain      the domain to block, along with its subdomains
     * @param rejectMedia whether to reject its attachments, which a suspension does anyway
     * @param comment     a note for admins, or null or empty for none
     * @throws InvalidDomainBlockException  if this domain can't be blocked
     * @throws DomainBlockConflictException if it's already blocked, or a broader block does as much
     */
    @Transactional
    public DomainBlock create(String domain, DomainBlock.Severity severity, boolean rejectMedia, String comment) {
        var name = DomainName.parse(domain);
        // Empty for an IP address, which parse has already refused to block.
        var ownHost = DomainName.normalizeHost(instance.baseUrl().getHost());
        if (ownHost.isPresent() && (ownHost.get().equals(name) || ownHost.get().endsWith("." + name))) {
            throw new InvalidDomainBlockException("This server can't block itself.");
        }
        var now = Instant.now();
        var block = new DomainBlock(Ids.timeOrdered(now), name, cleanComment(comment), now, severity, rejectMedia);
        // As on Mastodon, a subdomain can have a block that does more than a broader one, such as suspending it.
        var conflict = blocks.findCovering(DomainName.suffixes(name)).stream()
                .filter(existing -> existing.domain().equals(name) || !block.stricterThan(existing))
                .findFirst();
        if (conflict.isPresent()) {
            throw new DomainBlockConflictException(conflict.get());
        }
        if (!blocks.insert(block)) {
            // Another request got there since the check above, and has committed by now.
            throw new DomainBlockConflictException(blocks.findCovering(List.of(name)).stream().findFirst().orElse(block));
        }
        if (severity == DomainBlock.Severity.SUSPEND) {
            events.publishEvent(new DomainBlocked(block.id()));
        } else if (rejectMedia) {
            events.publishEvent(new DomainMediaRejected(block.id()));
        }
        return block;
    }

    /**
     * Changes what a block does. Making it a suspension removes what's stored from the server, as a new one does, and
     * rejecting its media removes the copies of its attachments. Neither comes back if that's undone.
     *
     * @param comment a note for admins, or null or empty for none
     * @return the block, if there is one with this ID
     */
    @Transactional
    public Optional<DomainBlock> update(UUID id, DomainBlock.Severity severity, boolean rejectMedia, String comment) {
        var cleaned = cleanComment(comment);
        return blocks.findAndLock(id).flatMap(old -> blocks.update(id, severity, rejectMedia, cleaned).map(updated -> {
            if (old.severity() != DomainBlock.Severity.SUSPEND && updated.severity() == DomainBlock.Severity.SUSPEND) {
                events.publishEvent(new DomainBlocked(id));
            } else if (!old.rejectsMedia() && updated.rejectsMedia()) {
                events.publishEvent(new DomainMediaRejected(id));
            }
            if (old.rejectsMedia() && !updated.rejectsMedia()) {
                events.publishEvent(new DomainMediaAllowed(updated.domain()));
            }
            return updated;
        }));
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
    @Transactional
    public boolean delete(UUID id) {
        var deleted = blocks.deleteReturning(id);
        deleted.filter(DomainBlock::rejectsMedia)
                .ifPresent(block -> events.publishEvent(new DomainMediaAllowed(block.domain())));
        return deleted.isPresent();
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
     * @return whether this host is covered by a suspension. A block that only limits a server doesn't count
     */
    public boolean isBlocked(String host) {
        return !blockedHosts(Collections.singleton(host)).isEmpty();
    }

    /**
     * @param host as for {@link #isBlocked}
     * @return whether this host's attachments are rejected, by a block that rejects them or by a suspension
     */
    public boolean rejectsMedia(String host) {
        return !covered(Collections.singleton(host), blocks::findMediaRejected).isEmpty();
    }

    /**
     * Like {@link #isBlocked}, with a single query for all of them.
     *
     * @return those hosts that are covered by a block, each as it was given here
     */
    public Set<String> blockedHosts(Collection<String> hosts) {
        return covered(hosts, blocks::findBlocked);
    }

    private static Set<String> covered(Collection<String> hosts, Function<Set<String>, Set<String>> find) {
        var suffixes = new HashMap<String, List<String>>();
        for (var host : hosts) {
            DomainName.normalizeHost(host).ifPresent(name -> suffixes.put(host, DomainName.suffixes(name)));
        }
        var all = suffixes.values().stream().flatMap(List::stream).collect(Collectors.toSet());
        if (all.isEmpty()) {
            return Set.of();
        }
        var blocked = find.apply(all);
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
        requireStorable(comment);
        return comment.strip();
    }

    /**
     * PostgreSQL can't store these, and would otherwise fail the request with an error nobody can act on.
     */
    private static void requireStorable(String comment) {
        for (var i = 0; i < comment.length(); i++) {
            var character = comment.charAt(i);
            var paired = Character.isHighSurrogate(character)
                    && i + 1 < comment.length()
                    && Character.isLowSurrogate(comment.charAt(i + 1));
            if (paired) {
                i++;
            } else if (character == '\0' || Character.isSurrogate(character)) {
                throw new InvalidDomainBlockException("Comments can't contain invalid characters.");
            }
        }
    }
}
