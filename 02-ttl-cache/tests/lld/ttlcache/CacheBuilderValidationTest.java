package lld.ttlcache;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;

final class CacheBuilderValidationTest {

    @Test
    void maximumSizeMustBePositive() {
        assertThrows(IllegalArgumentException.class, () ->
                CacheBuilder.<String, String>newBuilder().maximumSize(0));
        assertThrows(IllegalArgumentException.class, () ->
                CacheBuilder.<String, String>newBuilder().maximumSize(-5));
    }

    @Test
    void defaultTtlMustNotBeNegative() {
        assertThrows(IllegalArgumentException.class, () ->
                CacheBuilder.<String, String>newBuilder().defaultTtl(Duration.ofSeconds(-1)));
    }

    @Test
    void defaultTtlMustNotBeNull() {
        assertThrows(NullPointerException.class, () ->
                CacheBuilder.<String, String>newBuilder().defaultTtl(null));
    }

    @Test
    void reaperIntervalMustBePositive() {
        assertThrows(IllegalArgumentException.class, () ->
                CacheBuilder.<String, String>newBuilder().reaperInterval(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () ->
                CacheBuilder.<String, String>newBuilder().reaperInterval(Duration.ofSeconds(-1)));
    }

    @Test
    void evictionPolicyFactoryMustNotBeNull() {
        assertThrows(NullPointerException.class, () ->
                CacheBuilder.<String, String>newBuilder().evictionPolicy(null));
    }

    @Test
    void clockMustNotBeNull() {
        assertThrows(NullPointerException.class, () ->
                CacheBuilder.<String, String>newBuilder().clock(null));
    }

    @Test
    void listenerMustNotBeNull() {
        assertThrows(NullPointerException.class, () ->
                CacheBuilder.<String, String>newBuilder().addListener(null));
    }
}
