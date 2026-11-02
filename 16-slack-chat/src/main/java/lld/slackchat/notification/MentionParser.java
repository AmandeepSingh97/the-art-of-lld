package lld.slackchat.notification;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lld.slackchat.model.UserId;

/**
 * Parses {@code @username} mentions out of message content.
 *
 * <p>Deliberately stateless and dependency-light: it accepts a {@code usernameResolver}
 * function rather than depending directly on {@code UserRepository}, so it can be unit tested
 * without constructing a full repository and can be reused against any lookup source. This is
 * a small Strategy-flavoured injection point rather than a named pattern in its own right.
 */
public final class MentionParser {

    // Slack-style handles: letters, digits, dots, underscores, hyphens.
    private static final Pattern MENTION_PATTERN = Pattern.compile("@([A-Za-z0-9._-]+)");

    private final Function<String, Optional<UserId>> usernameResolver;

    public MentionParser(Function<String, Optional<UserId>> usernameResolver) {
        this.usernameResolver = usernameResolver;
    }

    /**
     * Extracts every {@code @handle} token from {@code content} and resolves it to a
     * {@link UserId} via the configured resolver, silently ignoring handles that do not
     * resolve to a known user (typo-tolerant: an invalid mention degrades to plain text
     * rather than failing message send).
     */
    public Set<UserId> parseMentions(String content) {
        Set<UserId> mentioned = new LinkedHashSet<>();
        Matcher matcher = MENTION_PATTERN.matcher(content);
        while (matcher.find()) {
            String handle = matcher.group(1);
            usernameResolver.apply(handle).ifPresent(mentioned::add);
        }
        return mentioned;
    }
}
