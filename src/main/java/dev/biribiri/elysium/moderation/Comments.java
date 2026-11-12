package dev.biribiri.elysium.moderation;

/**
 * What PostgreSQL can store of an admin's or a reporter's comment, which would otherwise fail the request with an error
 * nobody can act on.
 */
final class Comments {

    private Comments() {
    }

    /**
     * @return whether it has no NUL and no surrogate without its pair
     */
    static boolean isStorable(String comment) {
        for (var i = 0; i < comment.length(); i++) {
            var character = comment.charAt(i);
            var paired = Character.isHighSurrogate(character)
                    && i + 1 < comment.length()
                    && Character.isLowSurrogate(comment.charAt(i + 1));
            if (paired) {
                i++;
            } else if (character == '\0' || Character.isSurrogate(character)) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return what's storable of it, at most this many code points long, and stripped
     */
    static String truncate(String comment, int maxLength) {
        var builder = new StringBuilder();
        comment.codePoints()
                .filter(codePoint -> codePoint != 0 && Character.getType(codePoint) != Character.SURROGATE)
                .limit(maxLength)
                .forEach(builder::appendCodePoint);
        return builder.toString().strip();
    }
}
