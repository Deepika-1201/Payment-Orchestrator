package com.payments.gateway.merchant.web;

import com.payments.gateway.merchant.MerchantDirectory;
import com.payments.gateway.merchant.MerchantPrincipal;
import com.payments.gateway.shared.error.ErrorCode;
import com.payments.gateway.shared.json.JsonCodec;
import com.payments.gateway.shared.web.Mdc;
import com.payments.gateway.shared.web.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticates merchant API calls ({@code /v1/**} except PSP webhooks) with a secret API key. */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final MerchantDirectory merchants;
    private final JsonCodec json;

    public ApiKeyAuthFilter(MerchantDirectory merchants, JsonCodec json) {
        this.merchants = merchants;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/v1/") || path.startsWith("/v1/webhooks/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            ProblemResponses.write(response, json, ErrorCode.AUTHENTICATION_REQUIRED,
                    "Provide your secret API key as 'Authorization: Bearer sk_...'");
            return;
        }
        String apiKey = header.substring(BEARER.length()).trim();
        Optional<MerchantPrincipal> principal = apiKey.startsWith("sk_") && apiKey.length() <= 128
                ? merchants.authenticate(apiKey)
                : Optional.empty();
        if (principal.isEmpty()) {
            ProblemResponses.write(response, json, ErrorCode.INVALID_API_KEY, "The API key is invalid or revoked");
            return;
        }
        request.setAttribute(MerchantPrincipal.ATTRIBUTE, principal.get());
        MDC.put(Mdc.MERCHANT_ID, principal.get().merchantId());
        chain.doFilter(request, response);
    }
}
