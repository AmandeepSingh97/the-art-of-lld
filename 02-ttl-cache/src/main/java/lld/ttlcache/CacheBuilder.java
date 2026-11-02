package lld.ttlcache;

import lld.ttlcache.policy.EvictionPolicy;
import lld.ttlcache.policy.LruEvictionPolicy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Builder (GoF Builder pattern) for configuring and constructing a {@link TtlCache}.
 *
 * <p><b>Why a builder instead of a telescoping constructor:</b> {@code TtlCache} has
 * six independent, mostly-optional configuration axes (capacity, default TTL, eviction
 * strategy, reaper cadence, clock, listeners, listener dispatch executor). A constructor
 * covering every combination would need either a huge number of overloads or a single
 * constructor with several optional parameters defaulted via overloads anyway. A builder
 * gives named, self-documenting call sites ({@code .maximumSize(1000).defaultTtl(...)})
 * and lets validation happen once, in {@link #build()}, after all configuration is known.
 *
 * <p>Not thread-safe itself (a single builder instance is expected to be configured and
 * built from one thread, like essentially every builder in the JDK and common libraries);
 * the {@link TtlCache} it produces is fully thread-safe once built.
 *
 * @param <K> key type
 * @param <V> value type
 */
public final class CacheBuilder<K, V> {

    private long maximumSize = Long.MAX_VALUE;
    private Duration defaultTtl = Duration.ZERO;
    private Supplier<EvictionPolicy<K>> evictionPolicyFactory = LruEvictionPolicy::new;
    private Duration reaperInterval = Duration.ofSeconds(1);
    private Clock clock = Clock.systemClock();
    private Executor listenerExecutor = Runnable::run;
    private final List<CacheEvictionListener<K, V>> listeners = new ArrayList<>();

    private CacheBuilder() {
    }

    /**
     * @param <K> key type
     * @param <V> value type
     * @return a new builder with defaults: unbounded size, no default TTL (entries live
     *         forever unless a TTL is given explicitly per-entry or the key is evicted
     *         for size), LRU eviction, a 1-second reaper sweep interval, and the system
     *         clock.
     */
    public static <K, V> CacheBuilder<K, V> newBuilder() {
        return new CacheBuilder<>();
    }

    /**
     * Caps the number of entries the cache holds. Once at capacity, each {@code put} of a
     * new key triggers the eviction policy to choose a victim before (or immediately
     * after) the new entry is admitted, so the cache never exceeds this bound by more
     * than a transient, single-entry margin during concurrent inserts.
     *
     * @throws IllegalArgumentException if {@code maximumSize <= 0}
     */
    public CacheBuilder<K, V> maximumSize(long maximumSize) {
        if (maximumSize <= 0) {
            throw new IllegalArgumentException("maximumSize must be positive, got " + maximumSize);
        }
        this.maximumSize = maximumSize;
        return this;
    }

    /**
     * Sets the TTL applied to entries inserted via {@link Cache#put(Object, Object)} (the
     * overload without an explicit TTL) and via {@link Cache#computeIfAbsent(Object,
     * java.util.function.Function)}. {@link Duration#ZERO} (the default) means such
     * entries never expire on their own.
     *
     * @throws NullPointerException     if {@code defaultTtl} is {@code null}
     * @throws IllegalArgumentException if {@code defaultTtl} is negative
     */
    public CacheBuilder<K, V> defaultTtl(Duration defaultTtl) {
        Objects.requireNonNull(defaultTtl, "defaultTtl");
        if (defaultTtl.isNegative()) {
            throw new IllegalArgumentException("defaultTtl must not be negative, got " + defaultTtl);
        }
        this.defaultTtl = defaultTtl;
        return this;
    }

    /**
     * Supplies the size-based {@link EvictionPolicy} strategy, e.g.
     * {@code LruEvictionPolicy::new} or {@code LfuEvictionPolicy::new}. A factory (rather
     * than a pre-built instance) is required because a fresh policy instance is created
     * per {@code TtlCache}; sharing one policy instance across multiple caches would mix
     * unrelated keyspaces into one recency/frequency structure.
     *
     * @throws NullPointerException if {@code evictionPolicyFactory} is {@code null}
     */
    public CacheBuilder<K, V> evictionPolicy(Supplier<EvictionPolicy<K>> evictionPolicyFactory) {
        this.evictionPolicyFactory = Objects.requireNonNull(evictionPolicyFactory, "evictionPolicyFactory");
        return this;
    }

    /**
     * Sets how often the background reaper thread sweeps the cache for expired entries.
     * Shorter intervals reduce the "stale read window" (memory held by dead entries
     * between natural expiry and actual removal) at the cost of more frequent sweeps;
     * see the README's Performance section for the sizing trade-off.
     *
     * @throws NullPointerException     if {@code reaperInterval} is {@code null}
     * @throws IllegalArgumentException if {@code reaperInterval} is not positive
     */
    public CacheBuilder<K, V> reaperInterval(Duration reaperInterval) {
        Objects.requireNonNull(reaperInterval, "reaperInterval");
        if (reaperInterval.isZero() || reaperInterval.isNegative()) {
            throw new IllegalArgumentException("reaperInterval must be positive, got " + reaperInterval);
        }
        this.reaperInterval = reaperInterval;
        return this;
    }

    /**
     * Overrides the clock used for TTL computations. Intended for tests; production code
     * should not normally call this (defaults to {@link Clock#systemClock()}).
     *
     * @throws NullPointerException if {@code clock} is {@code null}
     */
    public CacheBuilder<K, V> clock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        return this;
    }

    /**
     * Registers an eviction listener. May be called multiple times to register several
     * listeners; all are notified, in registration order, for every removal.
     *
     * @throws NullPointerException if {@code listener} is {@code null}
     */
    public CacheBuilder<K, V> addListener(CacheEvictionListener<K, V> listener) {
        this.listeners.add(Objects.requireNonNull(listener, "listener"));
        return this;
    }

    /**
     * Overrides the {@link Executor} used to dispatch listener callbacks. By default
     * listeners run synchronously, inline on the thread that triggered the removal
     * ({@code Runnable::run}). Supply e.g. a dedicated single-threaded executor to
     * decouple potentially slow listener code from cache-operation latency and from the
     * reaper thread's sweep cadence.
     *
     * @throws NullPointerException if {@code listenerExecutor} is {@code null}
     */
    public CacheBuilder<K, V> listenerExecutor(Executor listenerExecutor) {
        this.listenerExecutor = Objects.requireNonNull(listenerExecutor, "listenerExecutor");
        return this;
    }

    /**
     * Validates the accumulated configuration and constructs the cache. Starts the
     * background reaper thread as part of construction &mdash; the returned cache is
     * immediately live and must eventually be {@link Cache#close() closed} to stop that
     * thread.
     */
    public TtlCache<K, V> build() {
        return new TtlCache<>(
                maximumSize,
                defaultTtl,
                evictionPolicyFactory.get(),
                reaperInterval,
                clock,
                List.copyOf(listeners),
                listenerExecutor);
    }
}
