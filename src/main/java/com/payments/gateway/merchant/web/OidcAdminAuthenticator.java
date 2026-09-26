package com.payments.gateway.merchant.web;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.payments.gateway.shared.config.SecurityProperties;
import com.payments.gateway.shared.web.AdminRole;
import java.net.MalformedURLException;
import java.text.ParseException;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verifies admin SSO access tokens (ADR-023): RS256/ES256 JWTs from the configured issuer and audience, signed by a
 * key from the issuer's JWKS (cached, refreshed on unknown key ids), with expiry checked (60 s skew).
 */
public class OidcAdminAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(OidcAdminAuthenticator.class);
    private static final int MAX_NAME_LENGTH = 128;

    private final ConfigurableJWTProcessor<SecurityContext> processor;
    private final String rolesClaim;
    private final String nameClaim;

    public OidcAdminAuthenticator(SecurityProperties.Oidc config) {
        JWKSource<SecurityContext> keys;
        try {
            keys = JWKSourceBuilder.<SecurityContext>create(config.jwksUri().toURL()).retrying(true).build();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("invalid pg.security.oidc.jwks-uri", e);
        }
        DefaultJWTProcessor<SecurityContext> jwtProcessor = new DefaultJWTProcessor<>();
        jwtProcessor.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(JOSEObjectType.JWT, new JOSEObjectType("at+jwt"), null));
        jwtProcessor.setJWSKeySelector(new JWSVerificationKeySelector<>(Set.of(JWSAlgorithm.RS256, JWSAlgorithm.ES256), keys));
        jwtProcessor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(config.audience(),
                new JWTClaimsSet.Builder().issuer(config.issuer().toString()).build(), Set.of("sub", "exp")));
        this.processor = jwtProcessor;
        this.rolesClaim = config.rolesClaim();
        this.nameClaim = config.nameClaim();
    }

    public static boolean looksLikeJwt(String token) {
        return token.chars().filter(c -> c == '.').count() == 2;
    }

    /** Empty when the token is not valid; a valid token without known roles yields a principal that may do nothing. */
    public Optional<AdminPrincipal> authenticate(String token) {
        JWTClaimsSet claims;
        try {
            claims = processor.process(token, null);
        } catch (ParseException | BadJOSEException | JOSEException e) {
            log.warn("Rejected admin SSO token: {}", e.getMessage());
            return Optional.empty();
        }
        String name = claimAsString(claims, nameClaim);
        if (name == null || name.isBlank()) {
            name = claims.getSubject();
        }
        return Optional.of(new AdminPrincipal(name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name,
                roles(claims.getClaim(rolesClaim))));
    }

    private static String claimAsString(JWTClaimsSet claims, String claim) {
        Object value = claims.getClaim(claim);
        return value instanceof String text ? text : null;
    }

    private static Set<AdminRole> roles(Object claim) {
        Collection<?> values = claim instanceof Collection<?> list ? list
                : claim instanceof String text ? Arrays.asList(text.split("[ ,]+")) : Set.of();
        Set<AdminRole> roles = EnumSet.noneOf(AdminRole.class);
        for (Object value : values) {
            String name = String.valueOf(value).trim().toUpperCase(Locale.ROOT).replace('-', '_');
            Arrays.stream(AdminRole.values()).filter(role -> role.name().equals(name)).forEach(roles::add);
        }
        return roles;
    }
}
