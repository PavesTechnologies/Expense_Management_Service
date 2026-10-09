package com.expense_management_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code rms.service-account.*} configuration namespace.
 * <p>
 * RMS's client-lookup endpoints only accept JWTs carrying role {@code Admin},
 * {@code Resource_Manager}, or {@code Project_Manager} — an ordinary employee's own
 * {@code GENERAL}-role JWT (the token XMS forwards to UMS/PMS) is rejected with 403. This is
 * a deliberate, narrow exception to XMS's stated "never mints, caches, or stores a token
 * itself" principle (see {@code RestClientConfig}): XMS logs into UMS as this dedicated
 * service account — provisioned in UMS with the {@code Resource_Manager} role — and uses the
 * resulting token only for outbound RMS calls, mirroring RMS's own precedent for calling UMS
 * (its {@code EXTERNAL_AUTH_EMAIL}/{@code EXTERNAL_AUTH_PASSWORD} pattern).
 * <p>
 * <b>Not yet confirmed:</b> the exact UMS login endpoint path and request/response shape this
 * account authenticates against — see {@code RmsServiceTokenProvider}.
 *
 * @param loginUri UMS's login/token endpoint this service account authenticates against
 * @param email    service account email/username (env-var sourced — never hard-coded)
 * @param password service account password (env-var sourced — never hard-coded)
 */
@ConfigurationProperties(prefix = "rms.service-account")
public record RmsServiceAuthProperties(String loginUri, String email, String password) {
}
