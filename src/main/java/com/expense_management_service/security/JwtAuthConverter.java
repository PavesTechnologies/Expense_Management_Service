package com.expense_management_service.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.expense_management_service.security.SecurityConstants.CLAIM_EMAIL;
import static com.expense_management_service.security.SecurityConstants.CLAIM_PERMISSIONS;
import static com.expense_management_service.security.SecurityConstants.CLAIM_ROLES;
import static com.expense_management_service.security.SecurityConstants.ROLE_PREFIX;

/**
 * Converts a validated UMS JWT into a Spring Security {@link AbstractAuthenticationToken}.
 * <p>
 * UMS embeds two independent claims:
 * <ul>
 *   <li>{@code roles} — coarse-grained names (e.g. {@code "General"}), upper-cased and
 *       prefixed with {@code ROLE_} so {@code hasRole(...)} works as Spring Security expects.</li>
 *   <li>{@code permissions} — fine-grained authority names (e.g. {@code "EXPENSE_CREATE"}),
 *       used verbatim as authorities so {@code hasAuthority(...)} matches directly.</li>
 * </ul>
 * No authentication or token issuance happens here — the token was already
 * cryptographically validated by {@code NimbusJwtDecoder} against the UMS JWKS
 * endpoint; this class only maps its claims onto Spring Security authorities.
 */
@Component
public class JwtAuthConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>();

        List<String> roles = jwt.getClaimAsStringList(CLAIM_ROLES);
        if (roles != null) {
            roles.stream()
                    .map(role -> ROLE_PREFIX + role.toUpperCase(Locale.ROOT))
                    .map(SimpleGrantedAuthority::new)
                    .forEach(authorities::add);
        }

        List<String> permissions = jwt.getClaimAsStringList(CLAIM_PERMISSIONS);
        if (permissions != null) {
            permissions.stream()
                    .map(SimpleGrantedAuthority::new)
                    .forEach(authorities::add);
        }

        String principalName = jwt.getClaimAsString(CLAIM_EMAIL) != null
                ? jwt.getClaimAsString(CLAIM_EMAIL)
                : jwt.getSubject();

        return new JwtAuthenticationToken(jwt, authorities, principalName);
    }
}
