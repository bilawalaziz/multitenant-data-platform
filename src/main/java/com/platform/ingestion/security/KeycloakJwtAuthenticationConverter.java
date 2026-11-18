package com.platform.ingestion.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Maps Keycloak {@code realm_access.roles} to the platform's ROLE_* authorities. Unknown roles are ignored. */
public class KeycloakJwtAuthenticationConverter implements Converter<Jwt, AbstractOAuth2TokenAuthenticationToken<Jwt>> {

    private static final Map<String, String> ROLE_MAPPING = Map.of(
            "platform-admin", "ROLE_" + Roles.PLATFORM_ADMIN,
            "tenant-admin", "ROLE_" + Roles.TENANT_ADMIN,
            "tenant-ingest", "ROLE_" + Roles.TENANT_INGEST);

    @Override
    public AbstractOAuth2TokenAuthenticationToken<Jwt> convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = new ArrayList<>();
        Object realmAccess = jwt.getClaim("realm_access");
        if (realmAccess instanceof Map<?, ?> map && map.get("roles") instanceof Collection<?> roles) {
            for (Object role : roles) {
                String authority = ROLE_MAPPING.get(String.valueOf(role));
                if (authority != null) {
                    authorities.add(new SimpleGrantedAuthority(authority));
                }
            }
        }
        String name = jwt.getClaimAsString("preferred_username");
        return new JwtAuthenticationToken(jwt, List.copyOf(authorities), name != null ? name : jwt.getSubject());
    }
}
