package com.expense_management_service.security;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The application's one {@link JwtDecoder}. The real decoder is built on first use, from UMS discovery, and then reused
 * for every authenticated request.
 *
 * <p>If building it fails (UMS down, timeout, bad response), the failure is remembered for {@link #DEFAULT_RETRY_AFTER}.
 * Requests in that window fail fast with {@link UmsUnavailableException} and do not contact UMS again. After the window
 * the next request tries once more.
 *
 * <p>Token validation is never bypassed. A token that fails validation, such as an expired or forged one, is rejected
 * by the real decoder and does not reset or rebuild anything.
 */
public class ResilientJwtDecoder implements JwtDecoder {

    /** How long to refuse requests after a failed attempt to load UMS configuration before trying again. */
    public static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(30);

    private final Supplier<JwtDecoder> factory;
    private final Clock clock;
    private final Duration retryAfter;
    private final Object lock = new Object();

    private volatile JwtDecoder decoder;
    /** Guarded by {@link #lock}. */
    private Instant nextAttemptAt = Instant.MIN;

    public ResilientJwtDecoder(Supplier<JwtDecoder> factory) {
        this(factory, Clock.systemUTC(), DEFAULT_RETRY_AFTER);
    }

    public ResilientJwtDecoder(Supplier<JwtDecoder> factory, Clock clock, Duration retryAfter) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        return decoder().decode(token);
    }

    /** True once the real decoder has been built and is being reused. */
    public boolean isInitialized() {
        return decoder != null;
    }

    private JwtDecoder decoder() {
        JwtDecoder ready = decoder;
        if (ready != null) {
            return ready;
        }
        synchronized (lock) {
            if (decoder != null) {
                return decoder;
            }
            Instant now = clock.instant();
            if (now.isBefore(nextAttemptAt)) {
                throw new UmsUnavailableException(
                        "The sign-in service is unavailable. Next attempt after " + nextAttemptAt + ".", null);
            }
            try {
                decoder = Objects.requireNonNull(factory.get(), "JwtDecoder factory returned null");
                return decoder;
            } catch (UmsUnavailableException e) {
                nextAttemptAt = now.plus(retryAfter);
                throw e;
            } catch (RuntimeException e) {
                nextAttemptAt = now.plus(retryAfter);
                throw new UmsUnavailableException("The sign-in service is unavailable.", e);
            }
        }
    }
}
