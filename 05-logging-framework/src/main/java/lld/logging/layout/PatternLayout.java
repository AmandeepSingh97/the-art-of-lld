package lld.logging.layout;

import lld.logging.core.LogEvent;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * A simplified version of log4j's {@code PatternLayout}: a conversion
 * pattern string containing the following conversion characters is expanded
 * per event:
 *
 * <pre>
 *   %d   ISO-8601 timestamp (UTC)
 *   %p   level ("priority")
 *   %c   logger name ("category")
 *   %t   thread name
 *   %m   message
 *   %n   platform line separator
 *   %%   literal '%'
 * </pre>
 *
 * <p>Unlike real log4j, this does not support field width/precision
 * modifiers (e.g. {@code %-5p}) or custom date patterns -- deliberately kept
 * small for interview scope; the README's "Trade-offs" section discusses
 * where a full mini-parser would be introduced if this needed to grow.
 *
 * <p>The pattern is parsed once, at construction time, into an immutable
 * list of {@link Token}s, so formatting an event is just a linear walk with
 * no re-parsing -- important since this runs on every log call (or, for
 * async appenders, on every dequeue).
 */
public final class PatternLayout implements Layout {

    public static final String DEFAULT_PATTERN = "%d %p [%t] %c - %m%n";

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.ROOT).withZone(ZoneId.of("UTC"));

    private sealed interface Token permits LiteralToken, ConversionToken {}
    private record LiteralToken(String text) implements Token {}
    private record ConversionToken(char conversionChar) implements Token {}

    private final String pattern;
    private final java.util.List<Token> tokens;

    public PatternLayout(String pattern) {
        this.pattern = Objects.requireNonNull(pattern, "pattern");
        this.tokens = parse(pattern);
    }

    public PatternLayout() {
        this(DEFAULT_PATTERN);
    }

    public String getPattern() {
        return pattern;
    }

    private static java.util.List<Token> parse(String pattern) {
        java.util.List<Token> result = new java.util.ArrayList<>();
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '%' && i + 1 < pattern.length()) {
                if (literal.length() > 0) {
                    result.add(new LiteralToken(literal.toString()));
                    literal.setLength(0);
                }
                char conv = pattern.charAt(++i);
                if (conv == '%') {
                    literal.append('%');
                } else {
                    result.add(new ConversionToken(conv));
                }
            } else {
                literal.append(c);
            }
        }
        if (literal.length() > 0) {
            result.add(new LiteralToken(literal.toString()));
        }
        return java.util.List.copyOf(result);
    }

    @Override
    public String format(LogEvent event) {
        StringBuilder sb = new StringBuilder(96);
        for (Token token : tokens) {
            if (token instanceof LiteralToken lt) {
                sb.append(lt.text());
            } else if (token instanceof ConversionToken ct) {
                appendConversion(sb, ct.conversionChar(), event);
            }
        }
        return sb.toString();
    }

    private static void appendConversion(StringBuilder sb, char conv, LogEvent event) {
        switch (conv) {
            case 'd' -> sb.append(TIMESTAMP_FORMATTER.format(event.getTimestamp()));
            case 'p' -> sb.append(event.getLevel());
            case 'c' -> sb.append(event.getLoggerName());
            case 't' -> sb.append(event.getThreadName());
            case 'm' -> {
                sb.append(event.getMessage());
                if (event.getThrowable() != null) {
                    SimpleLayout.appendThrowableIfPresent(sb, event);
                }
            }
            case 'n' -> sb.append(System.lineSeparator());
            default -> sb.append('%').append(conv); // unknown conversion: echo verbatim
        }
    }
}
