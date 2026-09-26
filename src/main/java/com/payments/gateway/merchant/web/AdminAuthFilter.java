package com.payments.gateway.merchant.web;

import com.payments.gateway.shared.crypto.Hashing;
import com.payments.gateway.shared.error.ErrorCode;
import com.payments.gateway.shared.json.JsonCodec;
import com.payments.gateway.shared.web.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates {@code /admin/**} callers by bearer token (ADR-019). Operators are matched by the SHA-256 of their
 * token; what they may do is enforced per endpoint by {@link AdminPermissionInterceptor}. Target state is OIDC SSO
 * with the same roles (architecture §8). With no credentials configured, every admin request is rejected.
 */
public class AdminAuthFilter extends OncePerRequestFilter {

    public static final String ACTOR_ATTRIBUTE = "pg.adminActor";
    public static final String PRINCIPAL_ATTRIBUTE = "pg.adminPrincipal";
    private static final String BEARER = "Bearer ";

    /** A configured admin credential: the principal it authenticates and the hex SHA-256 of its token. */
    public record Credential(AdminPrincipal principal, String tokenSha256) {
    }

    private final List<Credential> credentials;
    private final JsonCodec json;

    public AdminAuthFilter(List<Credential> credentials, JsonCodec json) {
        this.credentials = List.copyOf(credentials);
        this.json = json;
    }

    public static String sha256Hex(String token) {
        return HexFormat.of().formatHex(Hashing.sha256(token));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        String token = header != null && header.startsWith(BEARER) ? header.substring(BEARER.length()).trim() : null;
        AdminPrincipal principal = null;
        if (token != null && !token.isEmpty()) {
            String presented = sha256Hex(token);
            for (Credential credential : credentials) {
                if (Hashing.constantTimeEquals(credential.tokenSha256(), presented)) {
                    principal = credential.principal();
                }
            }
        }
        if (principal == null) {
            ProblemResponses.write(response, json, ErrorCode.AUTHENTICATION_REQUIRED, "Admin credentials required");
            return;
        }
        request.setAttribute(ACTOR_ATTRIBUTE, principal.name());
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
        chain.doFilter(request, response);
    }
}
