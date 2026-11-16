package com.expense_management_service.security;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Builds the UMS-backed {@link JwtDecoder} from the existing issuer URI.
 *
 * <p>Discovery is done here, through a {@link RestTemplate} with bounded timeouts, and the key set is fetched through the
 * same client. Spring's {@code NimbusJwtDecoder.withIssuerLocation} is not used, because it runs discovery through its own
 * client with the library's default timeouts, so the bound would not apply. Validation is unchanged: the discovered issuer
 * must equal the configured one, and tokens get the default timestamp and issuer checks.
 */
public final class UmsJwtDecoderFactory {

    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(4);
    public static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private static final String DISCOVERY_PATH = "/.well-known/openid-configuration";

    private UmsJwtDecoderFactory() {
    }

    /**
     * Runs OIDC discovery against {@code issuerUri} and returns a decoder for it. Throws if UMS cannot be reached within
     * the timeouts, or if its discovery document is missing the expected fields or names a different issuer.
     */
    public static JwtDecoder fromIssuer(String issuerUri) {
        RestTemplate rest = boundedRestTemplate();

        Map<?, ?> discovery = rest.getForObject(stripTrailingSlash(issuerUri) + DISCOVERY_PATH, Map.class);
        if (discovery == null) {
            throw new IllegalStateException("UMS returned an empty discovery document");
        }
        if (!issuerUri.equals(discovery.get("issuer"))) {
            throw new IllegalStateException("UMS discovery names a different issuer than the one configured");
        }
        String jwksUri = Objects.toString(discovery.get("jwks_uri"), null);
        if (jwksUri == null || jwksUri.isBlank()) {
            throw new IllegalStateException("UMS discovery document has no jwks_uri");
        }

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri)
                .restOperations(rest)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuerUri));
        return decoder;
    }

    private static RestTemplate boundedRestTemplate() {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(CONNECT_TIMEOUT);
        requests.setReadTimeout(READ_TIMEOUT);
        return new RestTemplate(requests);
    }

    private static String stripTrailingSlash(String uri) {
        return uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
    }
}
