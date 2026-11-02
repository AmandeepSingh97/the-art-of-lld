package lld.logging.layout;

import lld.logging.core.LogEvent;

/**
 * Minimal, zero-configuration layout: {@code LEVEL [thread] logger - message}.
 * Useful as a safe default and in tests where exact formatting doesn't
 * matter.
 */
public final class SimpleLayout implements Layout {

    @Override
    public String format(LogEvent event) {
        StringBuilder sb = new StringBuilder(64);
        sb.append(event.getLevel()).append(" [").append(event.getThreadName()).append("] ")
                .append(event.getLoggerName()).append(" - ").append(event.getMessage());
        appendThrowableIfPresent(sb, event);
        sb.append(System.lineSeparator());
        return sb.toString();
    }

    static void appendThrowableIfPresent(StringBuilder sb, LogEvent event) {
        if (event.getThrowable() != null) {
            sb.append(System.lineSeparator());
            java.io.StringWriter sw = new java.io.StringWriter();
            event.getThrowable().printStackTrace(new java.io.PrintWriter(sw));
            // Trim trailing newline from printStackTrace's output; our caller
            // appends the line separator itself.
            String trace = sw.toString();
            if (trace.endsWith(System.lineSeparator())) {
                trace = trace.substring(0, trace.length() - System.lineSeparator().length());
            }
            sb.append(trace);
        }
    }
}
