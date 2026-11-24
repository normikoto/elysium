package dev.biribiri.elysium.graph;

import dev.biribiri.elysium.actor.ActorDataRemoval;
import dev.biribiri.elysium.graph.internal.RelationshipFileRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * List members and endorsements reference the follows they depend on, so removing a follow removes them too.
 */
@Component
class GraphRemoval implements ActorDataRemoval {

    private final FollowService follows;
    private final BlockService blocks;
    private final MuteService mutes;
    private final AccountMemos memos;
    private final AccountDomainBlockService domainBlocks;
    private final AccountLists lists;
    private final RelationshipFileRepository imports;
    private final TagFollows tagFollows;

    GraphRemoval(FollowService follows, BlockService blocks, MuteService mutes, AccountMemos memos,
                 AccountDomainBlockService domainBlocks, AccountLists lists, RelationshipFileRepository imports,
                 TagFollows tagFollows) {
        this.follows = follows;
        this.blocks = blocks;
        this.mutes = mutes;
        this.memos = memos;
        this.domainBlocks = domainBlocks;
        this.lists = lists;
        this.imports = imports;
        this.tagFollows = tagFollows;
    }

    @Override
    public void removeForDeleted(UUID actorId) {
        follows.removeForActor(actorId);
        blocks.removeForActor(actorId);
        mutes.removeForActor(actorId);
        memos.removeForActor(actorId);
        domainBlocks.removeForAccount(actorId);
        lists.removeForActor(actorId);
        imports.deleteForAccount(actorId);
        tagFollows.removeForAccount(actorId);
    }

    /**
     * A suspended remote actor loses its follows, both ways, and its server isn't told.
     */
    @Override
    public void removeForSuspendedRemote(UUID actorId) {
        follows.removeForActor(actorId);
    }

    /**
     * Only the blocks that the server's actors made go. Local accounts' blocks, mutes, and memos of them are their own
     * choices.
     */
    @Override
    public void removeForDomain(String domain) {
        follows.removeForDomain(domain);
        blocks.removeForDomain(domain);
    }
}
