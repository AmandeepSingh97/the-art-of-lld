package lld.logging.config;

import lld.logging.appender.Appender;
import lld.logging.core.LogLevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable description of a complete logging setup: the root level, the
 * root's appenders, and a set of per-logger overrides. Built via the nested
 * {@link Builder} (Builder pattern) and applied to a {@link
 * lld.logging.core.LoggerContext} via {@link
 * lld.logging.core.LoggerContext#configure(LoggingConfiguration)}.
 *
 * <p>A builder (rather than, say, a constructor with a dozen parameters, or
 * mutable setters on {@code LoggerContext} itself) was chosen because: (1)
 * configuration has a natural "assemble piece by piece, then apply as one
 * atomic unit" shape -- you don't want a context to observe a half-built
 * configuration; (2) it reads declaratively, close to how a log4j.xml/
 * logback.xml file reads; (3) it keeps {@code LoggerContext} focused on
 * *runtime* hierarchy management rather than also being a configuration DSL.
 */
public final class LoggingConfiguration {

    private final LogLevel rootLevel;
    private final List<Appender> rootAppenders;
    private final List<LoggerConfig> loggerConfigs;

    private LoggingConfiguration(LogLevel rootLevel, List<Appender> rootAppenders, List<LoggerConfig> loggerConfigs) {
        this.rootLevel = rootLevel;
        this.rootAppenders = List.copyOf(rootAppenders);
        this.loggerConfigs = List.copyOf(loggerConfigs);
    }

    public LogLevel getRootLevel() {
        return rootLevel;
    }

    public List<Appender> getRootAppenders() {
        return rootAppenders;
    }

    public List<LoggerConfig> getLoggerConfigs() {
        return loggerConfigs;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private LogLevel rootLevel = LogLevel.INFO;
        private final List<Appender> rootAppenders = new ArrayList<>();
        // LinkedHashMap: preserves configuration order, which makes test
        // assertions and debug output deterministic.
        private final Map<String, LoggerConfigBuilder> loggerBuilders = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder rootLevel(LogLevel level) {
            this.rootLevel = Objects.requireNonNull(level, "level");
            return this;
        }

        public Builder addRootAppender(Appender appender) {
            this.rootAppenders.add(Objects.requireNonNull(appender, "appender"));
            return this;
        }

        /**
         * Begin (or resume) configuring a named logger. Returns a nested
         * builder scoped to that logger; call {@link LoggerConfigBuilder#and()}
         * to return to this builder, mirroring a typical fluent nested-DSL
         * shape used by configuration builders (e.g. Spring Security's DSL).
         */
        public LoggerConfigBuilder forLogger(String name) {
            Objects.requireNonNull(name, "name");
            return loggerBuilders.computeIfAbsent(name, LoggerConfigBuilder::new);
        }

        public LoggingConfiguration build() {
            List<LoggerConfig> configs = new ArrayList<>();
            for (LoggerConfigBuilder b : loggerBuilders.values()) {
                configs.add(b.build());
            }
            return new LoggingConfiguration(rootLevel, rootAppenders, configs);
        }

        public final class LoggerConfigBuilder {
            private final String name;
            private LogLevel level;
            private boolean additive = true;
            private final List<Appender> appenders = new ArrayList<>();

            private LoggerConfigBuilder(String name) {
                this.name = name;
            }

            public LoggerConfigBuilder level(LogLevel level) {
                this.level = level;
                return this;
            }

            public LoggerConfigBuilder additive(boolean additive) {
                this.additive = additive;
                return this;
            }

            public LoggerConfigBuilder addAppender(Appender appender) {
                this.appenders.add(Objects.requireNonNull(appender, "appender"));
                return this;
            }

            /** Returns to the enclosing {@link Builder} to continue configuration. */
            public Builder and() {
                return Builder.this;
            }

            private LoggerConfig build() {
                return new LoggerConfig(name, level, additive, appenders);
            }
        }
    }
}
