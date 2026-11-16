package com.expense_management_service.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResilientJwtDecoderTest {

    private static final Instant T0 = Instant.parse("2026-11-16T10:00:00Z");
    private static final Duration COOLDOWN = Duration.ofSeconds(30);
    private static final String SUBJECT = "EMP001";

    /** A clock the test moves forward by hand, so the cooldown can be crossed without sleeping. */
    static final class ManualClock extends Clock {
        private Instant now;

        ManualClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static Jwt fakeJwt() {
        return Jwt.withTokenValue("token").header("alg", "RS256").subject(SUBJECT)
                .issuedAt(T0).expiresAt(T0.plusSeconds(600)).build();
    }

    private static String signed(RSAKey key, Instant expiresAt) throws JOSEException {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                new JWTClaimsSet.Builder()
                        .subject(SUBJECT)
                        .issueTime(Date.from(expiresAt.minusSeconds(600)))
                        .expirationTime(Date.from(expiresAt))
                        .build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    @Test
    void sameDecoderIsReusedAcrossManyAuthenticatedRequests() {
        AtomicInteger builds = new AtomicInteger();
        JwtDecoder fake = token -> fakeJwt();
        ResilientJwtDecoder decoder = new ResilientJwtDecoder(() -> {
            builds.incrementAndGet();
            return fake;
        }, new ManualClock(T0), COOLDOWN);

        for (int i = 0; i < 25; i++) {
            assertEquals(SUBJECT, decoder.decode("token").getSubject());
        }

        assertEquals(1, builds.get(), "discovery must run once, not once per request");
        assertTrue(decoder.isInitialized());
    }

    @Test
    void unavailableUms_isRefusedWithoutRediscoveringDuringTheCooldown() {
        AtomicInteger attempts = new AtomicInteger();
        ManualClock clock = new ManualClock(T0);
        ResilientJwtDecoder decoder = new ResilientJwtDecoder(() -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("discovery timed out");
        }, clock, COOLDOWN);

        assertThrows(UmsUnavailableException.class, () -> decoder.decode("token"));
        clock.advance(Duration.ofSeconds(10));
        assertThrows(UmsUnavailableException.class, () -> decoder.decode("token"));
        clock.advance(Duration.ofSeconds(10));
        assertThrows(UmsUnavailableException.class, () -> decoder.decode("token"));

        assertEquals(1, attempts.get(), "requests inside the cooldown must not contact UMS");
        assertFalse(decoder.isInitialized());
    }

    @Test
    void afterTheCooldown_discoveryIsRetried_andTheDecoderRecovers() {
        AtomicInteger attempts = new AtomicInteger();
        ManualClock clock = new ManualClock(T0);
        JwtDecoder fake = token -> fakeJwt();
        ResilientJwtDecoder decoder = new ResilientJwtDecoder(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("UMS down");
            }
            return fake;
        }, clock, COOLDOWN);

        assertThrows(UmsUnavailableException.class, () -> decoder.decode("token"));
        clock.advance(COOLDOWN.plusSeconds(1));

        assertEquals(SUBJECT, decoder.decode("token").getSubject());
        assertEquals(SUBJECT, decoder.decode("token").getSubject());
        assertEquals(2, attempts.get(), "one failed attempt, then one successful one; none after recovery");
    }

    @Test
    void validSignedToken_isAccepted_andExpiredOrForgedTokensAreRejected() throws Exception {
        RSAKey issuerKey = new RSAKeyGenerator(2048).keyID("ums-key").generate();
        RSAKey forgerKey = new RSAKeyGenerator(2048).keyID("ums-key").generate();
        JwtDecoder realDecoder = NimbusJwtDecoder.withPublicKey(issuerKey.toRSAPublicKey()).build();
        AtomicInteger builds = new AtomicInteger();
        ResilientJwtDecoder decoder = new ResilientJwtDecoder(() -> {
            builds.incrementAndGet();
            return realDecoder;
        }, new ManualClock(T0), COOLDOWN);

        Instant now = Instant.now();
        assertEquals(SUBJECT, decoder.decode(signed(issuerKey, now.plusSeconds(600))).getSubject());
        assertThrows(JwtException.class, () -> decoder.decode(signed(issuerKey, now.minusSeconds(600))),
                "an expired token must be rejected");
        assertThrows(JwtException.class, () -> decoder.decode(signed(forgerKey, now.plusSeconds(600))),
                "a token signed by another key must be rejected");

        assertEquals(1, builds.get(), "rejecting a token must not rebuild or reset the decoder");
    }
}
