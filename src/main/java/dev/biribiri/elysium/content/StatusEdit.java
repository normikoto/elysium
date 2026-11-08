package dev.biribiri.elysium.content;

/**
 * What an author changes in one of their statuses. Its visibility and parent stay as they were, as on Mastodon.
 *
 * @param text        the source text, which replaces the old one
 * @param spoilerText the content warning, or null or blank for none
 * @param language    a language code, or null or blank to keep the one it has, as on Mastodon
 * @param sensitive   whether it's sensitive, or null to keep what it is. A content warning always makes it sensitive
 */
public record StatusEdit(String text, String spoilerText, String language, Boolean sensitive) {
}
