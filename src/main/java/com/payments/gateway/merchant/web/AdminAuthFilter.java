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
import java.util.List;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Protects {@code /admin/**} with static bearer tokens (V1). Target state is OIDC SSO with RBAC (see architecture §8).
 * With no tokens configured, every admin request is rejected.
 */
public class AdminAuthFilter extends OncePerRequestFilter {

    public static final String ACTOR_ATTRIBUTE = "pg.adminActor";
    private static final String BEARER = "Bearer ";

    private final List<String> tokens;
    private final JsonCodec json;

    public AdminAuthFilter(List<String> tokens, JsonCodec json) {
        this.tokens = List.copyOf(tokens);
        this.json = json;
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
        int match = -1;
        for (int i = 0; i < tokens.size(); i++) {
            if (Hashing.constantTimeEquals(tokens.get(i), token)) {
                match = i;
            }
        }
        if (match < 0) {
            ProblemResponses.write(response, json, ErrorCode.AUTHENTICATION_REQUIRED, "Admin credentials required");
            return;
        }
        request.setAttribute(ACTOR_ATTRIBUTE, "admin-token-" + match);
        chain.doFilter(request, response);
    }
}
