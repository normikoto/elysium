package dev.biribiri.elysium.graph;

import dev.biribiri.elysium.graph.internal.AccountNoteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Local accounts' private notes about other accounts, or about themselves, as Mastodon allows. Only the account that wrote
 * one sees it, in its {@link Relationship}, so notes are never federated.
 */
@Service
public class AccountNotes {

    /**
     * Mastodon's limit, in characters.
     */
    public static final int MAX_LENGTH = 2000;

    private final AccountNoteRepository notes;

    AccountNotes(AccountNoteRepository notes) {
        this.notes = notes;
    }

    /**
     * Replaces the account's note about the target. An empty or blank one removes it, as on Mastodon.
     *
     * @throws IllegalArgumentException if the note is longer than {@link #MAX_LENGTH}
     */
    @Transactional
    public void set(UUID accountId, UUID targetId, String note) {
        if (note == null || note.isBlank()) {
            notes.delete(accountId, targetId);
            return;
        }
        if (note.codePointCount(0, note.length()) > MAX_LENGTH) {
            throw new IllegalArgumentException("A note can be at most " + MAX_LENGTH + " characters");
        }
        notes.save(accountId, targetId, note);
    }

    /**
     * Removes the notes an actor wrote and those about it.
     */
    public void removeForActor(UUID actorId) {
        notes.deleteForActor(actorId);
    }
}
