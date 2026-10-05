package com.expense_management_service.integration.rms;

import com.expense_management_service.config.RmsServiceAuthProperties;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Logs the EMS service account into UMS and caches the resulting token for outbound RMS calls
 * — see {@link RmsServiceAuthProperties}'s javadoc for why this exists at all (RMS's role
 * requirements make the usual forwarded-caller-JWT pattern unusable for it).
 * <p>
 * UMS login contract, confirmed against the intranet frontend's own login page: {@code POST
 * /ums/auth/login {"email","password"} -> {"access_token", ...}}. {@link #login()} fails loudly
 * with a clear message if the required properties are unset, rather than silently producing a
 * broken client.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RmsServiceTokenProvider {

    /** Refresh this many seconds before actual expiry, to avoid a request racing an about-to-expire token. */
    private static final long EXPIRY_SAFETY_MARGIN_SECONDS = 60;

    /**
     * Deliberately not an injected bean — this app has no {@link ObjectMapper} bean registered
     * (confirmed: constructor-injecting one here failed context startup with "no bean of type
     * ObjectMapper found"), and all this needs is a bare-bones mapper to read one claim out of a
     * JWT payload, nothing tied to the app's HTTP-response Jackson configuration.
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RmsServiceAuthProperties rmsServiceAuthProperties;

    private final ReentrantLock lock = new ReentrantLock();
    private volatile String cachedToken;
    private volatile Instant cachedTokenExpiresAt = Instant.EPOCH;

    public String getToken() {
        if (isCachedTokenStillValid()) {
            return cachedToken;
        }
        lock.lock();
        try {
            if (isCachedTokenStillValid()) {
                return cachedToken;
            }
            login();
            return cachedToken;
        } finally {
            lock.unlock();
        }
    }

    /** Forces a fresh login on the next {@link #getToken()} call — used after RMS rejects the cached token with 401. */
    public void invalidate() {
        cachedToken = null;
        cachedTokenExpiresAt = Instant.EPOCH;
    }

    private boolean isCachedTokenStillValid() {
        return cachedToken != null
                && Instant.now().isBefore(cachedTokenExpiresAt.minusSeconds(EXPIRY_SAFETY_MARGIN_SECONDS));
    }

    private void login() {
        // hasText, not a null check — application.properties defaults these to empty strings
        // (${...:}), so an unset env var arrives here as "" and would otherwise be POSTed to as a
        // relative URI ("URI with undefined scheme").
        if (!StringUtils.hasText(rmsServiceAuthProperties.loginUri())
                || !StringUtils.hasText(rmsServiceAuthProperties.email())
                || !StringUtils.hasText(rmsServiceAuthProperties.password())) {
            throw new IllegalStateException(
                    "RMS service-account credentials are not configured — set "
                            + "RMS_SERVICE_ACCOUNT_LOGIN_URI, RMS_SERVICE_ACCOUNT_EMAIL and "
                            + "RMS_SERVICE_ACCOUNT_PASSWORD. RMS client lookups cannot work until "
                            + "they are set. See RmsServiceAuthProperties.");
        }

        UmsLoginResponse response = RestClient.create().post()
                .uri(rmsServiceAuthProperties.loginUri())
                .body(new UmsLoginRequest(rmsServiceAuthProperties.email(), rmsServiceAuthProperties.password()))
                .retrieve()
                .body(UmsLoginResponse.class);

        if (response == null || response.accessToken() == null) {
            throw new IllegalStateException("UMS login for the RMS service account returned no access token");
        }

        cachedToken = response.accessToken();
        cachedTokenExpiresAt = resolveExpiry(response.accessToken());
        log.info("Refreshed EMS service-account token for RMS calls, expires at {}", cachedTokenExpiresAt);
    }

    /**
     * Decodes the JWT's own {@code exp} claim rather than trusting a separate expires-in field
     * in the login response, so caching stays correct even if that field's name/presence changes.
     */
    private Instant resolveExpiry(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            Map<?, ?> claims = OBJECT_MAPPER.readValue(payloadJson, Map.class);
            if (claims.get("exp") instanceof Number exp) {
                return Instant.ofEpochSecond(exp.longValue());
            }
        } catch (Exception ex) {
            log.warn("Could not decode expiry from the RMS service-account token — defaulting to a short 5-minute cache", ex);
        }
        return Instant.now().plusSeconds(300);
    }

    private record UmsLoginRequest(String email, String password) {
    }

    private record UmsLoginResponse(@JsonAlias({"access_token", "token"}) String accessToken) {
    }
}
