package com.payments.gateway.shared.config;

import com.payments.gateway.shared.web.AdminRole;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code apiKeyMode} is per environment (ADR-014): a sandbox deployment issues {@code sk_test_} keys and talks to PSP
 * sandboxes, production issues {@code sk_live_} keys. Test and live data never share a database.
 *
 * <p>Admin callers (ADR-019): {@code adminUsers} are named operators with roles, identified by the SHA-256 of their
 * bearer token, so the configuration never holds a usable token. {@code adminTokens} are plaintext break-glass tokens
 * with the {@code ADMIN} role, meant for local development.
 */
@ConfigurationProperties("pg.security")
public record SecurityProperties(List<String> adminTokens, List<AdminUser> adminUsers, Oidc oidc, String dataEncryptionKey,
                                 ApiKeyMode apiKeyMode) {

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    public enum ApiKeyMode {
        TEST,
        LIVE;

        public String prefix() {
            return "sk_" + name().toLowerCase(Locale.ROOT) + "_";
        }
    }

    /** {@code name} is the actor recorded in the audit log. */
    public record AdminUser(String name, String tokenSha256, Set<AdminRole> roles) {

        public AdminUser {
            if (name == null || !NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("pg.security.admin-users: name must be a lowercase operator id, was " + name);
            }
            if (tokenSha256 == null || !SHA256_HEX.matcher(tokenSha256).matches()) {
                throw new IllegalArgumentException("pg.security.admin-users[" + name
                        + "]: token-sha256 must be the 64 lowercase hex characters of the token's SHA-256");
            }
            if (roles == null || roles.isEmpty()) {
                throw new IllegalArgumentException("pg.security.admin-users[" + name + "]: at least one role is required");
            }
            roles = Set.copyOf(roles);
        }
    }

    /**
     * Admin single sign-on (ADR-023): bearer JWTs issued by {@code issuer} for {@code audience}, verified against
     * {@code jwksUri}. {@code rolesClaim} lists admin roles; {@code nameClaim} becomes the audit actor.
     */
    public record Oidc(URI issuer, URI jwksUri, String audience, String rolesClaim, String nameClaim) {

        public Oidc {
            if (issuer != null && (jwksUri == null || audience == null || audience.isBlank())) {
                throw new IllegalArgumentException("pg.security.oidc needs jwks-uri and audience when issuer is set");
            }
            rolesClaim = rolesClaim == null ? "roles" : rolesClaim;
            nameClaim = nameClaim == null ? "preferred_username" : nameClaim;
        }

        public boolean enabled() {
            return issuer != null;
        }
    }

    public SecurityProperties {
        adminTokens = adminTokens == null ? List.of() : adminTokens.stream().filter(t -> t != null && !t.isBlank()).toList();
        adminUsers = adminUsers == null ? List.of() : List.copyOf(adminUsers);
        oidc = oidc == null ? new Oidc(null, null, null, null, null) : oidc;
        Set<String> names = new HashSet<>();
        Set<String> hashes = new HashSet<>();
        for (AdminUser user : adminUsers) {
            if (!names.add(user.name()) || !hashes.add(user.tokenSha256())) {
                throw new IllegalArgumentException("pg.security.admin-users: names and tokens must be unique (" + user.name() + ")");
            }
        }
        apiKeyMode = apiKeyMode == null ? ApiKeyMode.TEST : apiKeyMode;
    }
}
