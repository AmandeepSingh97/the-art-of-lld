package lld.logging.layout;

import lld.logging.core.LogEvent;

/**
 * Compact single-line JSON layout, handy for log shippers (Fluentd/Logstash)
 * that parse each line as a JSON document. Hand-rolled escaping is used
 * instead of pulling in a JSON library -- deliberate for interview scope
 * (no external dependency) and sufficient because the value set is narrow
 * and known (strings and a long timestamp).
 */
public final class JsonLayout implements Layout {

    @Override
    public String format(LogEvent event) {
        StringBuilder sb = new StringBuilder(128);
        sb.append('{');
        sb.append("\"timestamp\":").append(event.getTimestampMillis()).append(',');
        sb.append("\"level\":\"").append(event.getLevel()).append("\",");
        sb.append("\"logger\":\"").append(escape(event.getLoggerName())).append("\",");
        sb.append("\"thread\":\"").append(escape(event.getThreadName())).append("\",");
        sb.append("\"message\":\"").append(escape(event.getMessage())).append('"');
        if (event.getThrowable() != null) {
            sb.append(",\"exception\":\"").append(escape(stackTraceOf(event.getThrowable()))).append('"');
        }
        sb.append('}').append(System.lineSeparator());
        return sb.toString();
    }

    private static String stackTraceOf(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
