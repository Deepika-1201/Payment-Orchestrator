package com.payments.gateway.merchant.web;

import com.payments.gateway.shared.error.ErrorCode;
import com.payments.gateway.shared.json.JsonCodec;
import com.payments.gateway.shared.web.AdminPermission;
import com.payments.gateway.shared.web.ProblemResponses;
import com.payments.gateway.shared.web.RequiresAdmin;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces {@link RequiresAdmin} on admin endpoints (ADR-019). Deny by default: a non-{@code GET} admin endpoint
 * without the annotation is refused, so a new endpoint cannot ship unprotected.
 */
public class AdminPermissionInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AdminPermissionInterceptor.class);

    private final JsonCodec json;
    private final MeterRegistry meters;

    public AdminPermissionInterceptor(JsonCodec json, MeterRegistry meters) {
        this.json = json;
        this.meters = meters;
        for (AdminPermission permission : AdminPermission.values()) {
            meters.counter("pg.admin.denied", "permission", permission.name().toLowerCase(Locale.ROOT));
        }
    }

    /** The permission a handler requires, or null when it is undeclared (and therefore denied). */
    public static AdminPermission requiredPermission(HandlerMethod handler, String httpMethod) {
        RequiresAdmin declared = handler.getMethodAnnotation(RequiresAdmin.class);
        if (declared != null) {
            return declared.value();
        }
        return "GET".equals(httpMethod) || "HEAD".equals(httpMethod) ? AdminPermission.READ : null;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        AdminPermission required = requiredPermission(method, request.getMethod());
        if (required == null) {
            log.error("Admin endpoint {} declares no @RequiresAdmin permission; denying", method.getShortLogMessage());
        }
        Object principal = request.getAttribute(AdminAuthFilter.PRINCIPAL_ATTRIBUTE);
        if (required != null && principal instanceof AdminPrincipal admin && admin.can(required)) {
            return true;
        }
        String permission = required == null ? "undeclared" : required.name().toLowerCase(Locale.ROOT);
        log.warn("Admin {} denied {} {} (requires {})", principal instanceof AdminPrincipal admin ? admin.name() : "unknown",
                request.getMethod(), request.getRequestURI(), permission);
        meters.counter("pg.admin.denied", "permission", permission).increment();
        ProblemResponses.write(response, json, ErrorCode.FORBIDDEN, "This operation requires the " + permission + " permission");
        return false;
    }
}
