package com.expense_management_service.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A local stand-in for UMS in tests: OIDC discovery and a JWKS endpoint, served over loopback. The real discovery, key
 * fetch, signature and expiry checks all run against it, with no external network. Key material is generated per JVM
 * and is test-only.
 */
final class UmsStub {

    static final RSAKey KEY = generateKey();
    static final AtomicInteger DISCOVERY_HITS = new AtomicInteger();
    static final AtomicInteger JWKS_HITS = new AtomicInteger();
    static final String ISSUER;

    private static final HttpServer SERVER;

    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            ISSUER = "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/ums";
            SERVER.createContext("/ums/.well-known/openid-configuration", exchange -> {
                DISCOVERY_HITS.incrementAndGet();
                reply(exchange, "{\"issuer\":\"" + ISSUER + "\",\"jwks_uri\":\"" + ISSUER + "/jwks\"}");
            });
            SERVER.createContext("/ums/jwks", exchange -> {
                JWKS_HITS.incrementAndGet();
                reply(exchange, "{\"keys\":[" + KEY.toPublicJWK().toJSONString() + "]}");
            });
            SERVER.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> SERVER.stop(0)));
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private UmsStub() {
    }

    /** A token signed with {@code key}, for the stub issuer, expiring at {@code expiresAt}. */
    static String token(RSAKey key, Instant expiresAt, List<String> roles) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                    new JWTClaimsSet.Builder()
                            .issuer(ISSUER)
                            .subject("EMP001")
                            .claim("employee_id", "EMP001")
                            .claim("roles", roles)
                            .issueTime(Date.from(expiresAt.minusSeconds(600)))
                            .expirationTime(Date.from(expiresAt))
                            .build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A key with the same id as the stub's, but not the one UMS publishes. Used to forge a token. */
    static RSAKey forgerKey() {
        return generateKey();
    }

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("ums-web-test").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void reply(HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
