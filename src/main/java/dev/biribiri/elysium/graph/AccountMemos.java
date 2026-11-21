package dev.biribiri.elysium.graph;

import dev.biribiri.elysium.graph.internal.AccountMemoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Local accounts' private memos about other accounts, or about themselves, which Mastodon calls notes. Only the account
 * that wrote one sees it, in its {@link Relationship}, so memos are never federated.
 */
@Service
public class AccountMemos {

    /**
     * Mastodon's limit, in characters.
     */
    public static final int MAX_LENGTH = 2000;

    private final AccountMemoRepository memos;

    AccountMemos(AccountMemoRepository memos) {
        this.memos = memos;
    }

    /**
     * Replaces the account's memo about the target. An empty or blank one removes it, as on Mastodon.
     *
     * @throws IllegalArgumentException if the memo is longer than {@link #MAX_LENGTH}, or isn't {@link #isStorable}
     */
    @Transactional
    public void set(UUID accountId, UUID targetId, String memo) {
        if (memo == null || memo.isBlank()) {
            memos.delete(accountId, targetId);
            return;
        }
        if (!isStorable(memo)) {
            throw new IllegalArgumentException("A memo can't contain NUL characters or unpaired surrogates");
        }
        if (memo.codePointCount(0, memo.length()) > MAX_LENGTH) {
            throw new IllegalArgumentException("A memo can be at most " + MAX_LENGTH + " characters");
        }
        memos.save(accountId, targetId, memo);
    }

    /**
     * PostgreSQL can't store a NUL or a surrogate without its pair, which would otherwise fail the request with an error
     * nobody can act on.
     *
     * @return whether it has neither
     */
    public static boolean isStorable(String memo) {
        for (var i = 0; i < memo.length(); i++) {
            var character = memo.charAt(i);
            var paired = Character.isHighSurrogate(character)
                    && i + 1 < memo.length()
                    && Character.isLowSurrogate(memo.charAt(i + 1));
            if (paired) {
                i++;
            } else if (character == '\0' || Character.isSurrogate(character)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Removes the memos an actor wrote and those about it.
     */
    public void removeForActor(UUID actorId) {
        memos.deleteForActor(actorId);
    }
}
