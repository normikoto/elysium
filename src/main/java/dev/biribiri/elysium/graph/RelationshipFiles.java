package dev.biribiri.elysium.graph;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.RemoteActorResolver;
import dev.biribiri.elysium.actor.RemoteActorResolver.ResolveLimitException;
import dev.biribiri.elysium.graph.internal.RelationshipFileRepository;
import dev.biribiri.elysium.graph.internal.RelationshipFileRepository.Progress;
import dev.biribiri.elysium.graph.internal.RelationshipFileRepository.Row;
import dev.biribiri.elysium.jobs.Jobs;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Follows, blocks, or mutes the accounts listed in a CSV file, and writes those files, as Mastodon's settings do. An import
 * only adds: it never undoes a follow, block, or mute that isn't in the file.
 *
 * <p>Accounts on other servers may have to be looked up, which takes requests and counts toward the importing account's
 * {@link RemoteActorResolver#LOOKUPS_PER_MINUTE}, so a job works through the file a batch at a time and waits whenever it
 * reaches that limit.
 */
@Service
public class RelationshipFiles {

    private static final TaskDescriptor<RelationshipImportJob> TASK =
            TaskDescriptor.of("relationship-import", RelationshipImportJob.class);

    /**
     * Far more than {@link #MAX_ROWS} addresses take, so that only files that aren't lists of accounts are refused for
     * their size.
     */
    public static final long MAX_FILE_SIZE = 2 * 1024 * 1024;

    public static final int MAX_ROWS = RelationshipCsv.MAX_ROWS;

    /**
     * Small enough that a run doesn't hold a job thread long, since most rows can need a lookup.
     */
    private static final int BATCH = 50;

    private static final Duration LIMIT_WAIT = Duration.ofMinutes(1);

    private final RelationshipFileRepository imports;
    private final ActorService actors;
    private final InstanceProperties instance;
    private final RemoteActorResolver resolver;
    private final FollowService follows;
    private final BlockService blocks;
    private final MuteService mutes;
    private final SchedulerClient scheduler;

    /**
     * @param scheduler lazy like everywhere else it's used, so that this doesn't affect when the scheduler is created
     */
    RelationshipFiles(
            RelationshipFileRepository imports,
            ActorService actors,
            InstanceProperties instance,
            RemoteActorResolver resolver,
            FollowService follows,
            BlockService blocks,
            MuteService mutes,
            @Lazy SchedulerClient scheduler) {
        this.imports = imports;
        this.actors = actors;
        this.instance = instance;
        this.resolver = resolver;
        this.follows = follows;
        this.blocks = blocks;
        this.mutes = mutes;
        this.scheduler = scheduler;
    }

    @Bean
    static Task<RelationshipImportJob> importRelationships(ObjectProvider<RelationshipFiles> imports) {
        return Jobs.task(TASK, job -> imports.getObject().run(job));
    }

    /**
     * Starts importing a file, which replaces the account's last finished import.
     *
     * @throws InvalidRelationshipFileException if the file can't be read, or the account has an import running already
     */
    @Transactional
    public void start(UUID accountId, RelationshipKind kind, byte[] file) {
        var rows = RelationshipCsv.read(kind, new String(file, StandardCharsets.UTF_8));
        var importId = UUID.randomUUID();
        if (!imports.insert(importId, accountId, kind, rows)) {
            throw new InvalidRelationshipFileException("Your last import is still running. Wait until it's finished");
        }
        Jobs.schedule(scheduler, TASK, new RelationshipImportJob(importId, Instant.now()), importId.toString());
    }

    /**
     * @return the account's import that's running, or else the last one to finish
     */
    public Optional<Progress> latest(UUID accountId) {
        return imports.findLatest(accountId);
    }

    /**
     * Writes the accounts an account follows, blocks, or mutes as a file that this server and Mastodon can import.
     */
    public void export(UUID accountId, RelationshipKind kind, OutputStream out) throws IOException {
        var accounts = switch (kind) {
            case FOLLOWS -> imports.findFollowed(accountId);
            case BLOCKS -> imports.findBlocked(accountId);
            case MUTES -> imports.findMuted(accountId);
        };
        RelationshipCsv.write(kind, accounts, out);
    }

    /**
     * Each row goes once it's handled, and following, blocking, and muting again change nothing, so a run that's repeated
     * after it stopped partway only does what's left.
     */
    CompletionHandler<RelationshipImportJob> run(RelationshipImportJob job) {
        var running = imports.findRunning(job.importId());
        if (running.isEmpty()) {
            return Jobs.done();
        }
        var account = actors.findLocalById(running.get().accountId());
        if (account.isEmpty()) {
            imports.delete(job.importId());
            return Jobs.done();
        }
        var rows = imports.findRows(job.importId(), BATCH);
        if (rows.isEmpty()) {
            imports.finish(job.importId());
            return Jobs.done();
        }
        for (var row : rows) {
            Optional<Actor> target;
            try {
                target = find(account.get(), row);
            } catch (ResolveLimitException e) {
                return Jobs.rescheduleAt(Instant.now().plus(LIMIT_WAIT));
            }
            var added = target.isPresent() && add(running.get().kind(), account.get(), target.get(), row);
            imports.deleteRow(job.importId(), row.position(), !added);
        }
        return Jobs.rescheduleAt(Instant.now());
    }

    /**
     * An address without a domain is a local account's, as on Mastodon.
     */
    private Optional<Actor> find(Actor account, Row row) {
        if (row.domain() == null || instance.isLocalDomain(row.domain())) {
            return actors.findLocalByUsername(row.username());
        }
        return resolver.resolveAcct(account.id(), row.username(), row.domain());
    }

    /**
     * @return false if the account can't follow, block, or mute this one
     */
    private boolean add(RelationshipKind kind, Actor account, Actor target, Row row) {
        if (target.id().equals(account.id())) {
            return false;
        }
        try {
            switch (kind) {
                case FOLLOWS -> {
                    // The follow API checks the instance's server blocks before following, which FollowService leaves
                    // to its callers.
                    if (!imports.isReachable(target.id())) {
                        return false;
                    }
                    follows.follow(account, target, new FollowOptions(Optional.ofNullable(row.showReblogs()),
                            Optional.ofNullable(row.notifies()), Optional.ofNullable(row.languages())));
                }
                case BLOCKS -> blocks.block(account, target);
                // A mute hides notifications too unless the file says otherwise, as the mute API does.
                case MUTES -> mutes.mute(account.id(), target.id(),
                        row.hideNotifications() == null || row.hideNotifications(), Optional.empty());
            }
            return true;
        } catch (FollowNotAllowedException | IllegalArgumentException e) {
            return false;
        }
    }
}
