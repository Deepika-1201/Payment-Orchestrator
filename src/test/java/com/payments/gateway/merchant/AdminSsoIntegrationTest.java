package com.payments.gateway.merchant;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.payments.gateway.support.IntegrationTest;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** Admin single sign-on (ADR-023) against a local identity provider's JWKS. */
class AdminSsoIntegrationTest extends IntegrationTest {

    private static final String ISSUER = "https://idp.example.test/realms/operations";
    private static final String AUDIENCE = "payment-gateway-admin";
    private static final RSAKey PUBLISHED = key("sso-key-1");
    private static final RSAKey UNPUBLISHED = key("sso-key-2");
    private static final HttpServer IDP = jwksServer();

    @DynamicPropertySource
    static void sso(DynamicPropertyRegistry registry) {
        registry.add("pg.security.oidc.issuer", () -> ISSUER);
        registry.add("pg.security.oidc.jwks-uri", () -> "http://127.0.0.1:" + IDP.getAddress().getPort() + "/jwks");
        registry.add("pg.security.oidc.audience", () -> AUDIENCE);
    }

    @AfterAll
    static void stopIdp() {
        IDP.stop(0);
    }

    @Test
    void ssoOperatorsActWithTheirIdentityProviderRolesAndName() throws Exception {
        TestMerchant merchant = createMerchant(ALPHA);
        String ops = signed(PUBLISHED, JWSAlgorithm.RS256, claims(ISSUER, AUDIENCE, "priya@corp.example", List.of("ops"), 300));

        assertThat(as(ops, "GET", "/admin/v1/merchants/" + merchant.id(), null).status()).isEqualTo(200);
        Response suspended = as(ops, "POST", "/admin/v1/merchants/" + merchant.id() + "/suspend", Map.of("reason", "sso"));
        assertThat(suspended.status()).as(suspended.raw()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT actor_id FROM audit_log WHERE action = 'merchant.suspended'").query(String.class).single())
                .isEqualTo("priya@corp.example");
        assertThat(as(ops, "POST", "/admin/v1/routing-rules", Map.of()).status()).isEqualTo(403);
        String noRoles = signed(PUBLISHED, JWSAlgorithm.RS256, claims(ISSUER, AUDIENCE, "guest@corp.example", List.of(), 300));
        assertThat(as(noRoles, "GET", "/admin/v1/reviews", null).status()).as("authenticated, but no role").isEqualTo(403);
        assertThat(admin("GET", "/admin/v1/reviews", null).status()).as("break-glass token still works").isEqualTo(200);
    }

    @Test
    void forgedExpiredAndMisdirectedTokensAreRejected() throws Exception {
        List<String> roles = List.of("admin");
        String valid = signed(PUBLISHED, JWSAlgorithm.RS256, claims(ISSUER, AUDIENCE, "eve@corp.example", roles, 300));
        String[] parts = valid.split("\\.");
        String elevatedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                claims(ISSUER, AUDIENCE, "mallory@corp.example", roles, 300).toString().getBytes(StandardCharsets.UTF_8));

        Map<String, String> forged = Map.of(
                "expired", signed(PUBLISHED, JWSAlgorithm.RS256, claims(ISSUER, AUDIENCE, "eve@corp.example", roles, -300)),
                "wrong audience", signed(PUBLISHED, JWSAlgorithm.RS256, claims(ISSUER, "another-api", "eve@corp.example", roles, 300)),
                "wrong issuer", signed(PUBLISHED, JWSAlgorithm.RS256, claims("https://evil.example", AUDIENCE, "eve@corp.example", roles, 300)),
                "unpublished key", signed(UNPUBLISHED, JWSAlgorithm.RS256, claims(ISSUER, AUDIENCE, "eve@corp.example", roles, 300)),
                "alg none", new PlainJWT(claims(ISSUER, AUDIENCE, "eve@corp.example", roles, 300)).serialize(),
                "HS256 with the public key", hmacWithPublicKey(claims(ISSUER, AUDIENCE, "eve@corp.example", roles, 300)),
                "tampered payload", parts[0] + "." + elevatedPayload + "." + parts[2]);

        forged.forEach((kind, token) -> assertThat(as(token, "GET", "/admin/v1/reviews", null).status()).as(kind).isEqualTo(401));
        assertThat(as(valid, "GET", "/admin/v1/reviews", null).status()).isEqualTo(200);
    }

    private Response as(String token, String method, String path, Object body) {
        return send(method, path, Map.of("Authorization", "Bearer " + token), body);
    }

    private static JWTClaimsSet claims(String issuer, String audience, String name, List<String> roles, long expiresInSeconds) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .subject("user-" + name.hashCode())
                .claim("preferred_username", name)
                .claim("roles", roles)
                .issueTime(Date.from(now.minus(Duration.ofSeconds(Math.max(0, -expiresInSeconds) + 60))))
                .expirationTime(Date.from(now.plusSeconds(expiresInSeconds)))
                .build();
    }

    private static String signed(RSAKey key, JWSAlgorithm algorithm, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(algorithm).keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    /** The classic key-confusion attack: an HMAC "signed" with the published RSA public key. */
    private static String hmacWithPublicKey(JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(PUBLISHED.getKeyID()).build(), claims);
        byte[] secret = PUBLISHED.toPublicJWK().toRSAPublicKey().getEncoded();
        jwt.sign(new MACSigner(java.util.Arrays.copyOf(secret, Math.max(secret.length, 32))));
        return jwt.serialize();
    }

    private static RSAKey key(String id) {
        try {
            return new RSAKeyGenerator(2048).keyID(id).generate();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer jwksServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] jwks = new JWKSet(PUBLISHED.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/jwks", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                exchange.getResponseBody().write(jwks);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
