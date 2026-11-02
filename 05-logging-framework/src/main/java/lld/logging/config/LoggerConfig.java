package lld.logging.config;

import lld.logging.appender.Appender;
import lld.logging.core.LogLevel;

import java.util.List;
import java.util.Objects;

/**
 * Immutable value object describing the desired configuration for one named
 * logger: its explicit level (or {@code null} to inherit), its additivity
 * flag, and the appenders to attach. Produced only via {@link
 * LoggingConfiguration.Builder#forLogger(String)}.
 */
public record LoggerConfig(String name, LogLevel level, boolean additive, List<Appender> appenders) {

    public LoggerConfig {
        Objects.requireNonNull(name, "name");
        appenders = List.copyOf(appenders);
    }
}
