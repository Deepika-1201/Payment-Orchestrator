package com.payments.gateway.merchant;

import com.payments.gateway.merchant.web.AdminPermissionInterceptor;
import com.payments.gateway.support.IntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static com.payments.gateway.support.JsonPath.str;
import static org.assertj.core.api.Assertions.assertThat;

/** Admin roles (ADR-019): operators from application-test.yml, identified by token hash. */
class AdminRolesIntegrationTest extends IntegrationTest {

    private static final String OPS = "test-ops-token";
    private static final String FINANCE = "test-finance-token";
    private static final String READ_ONLY = "test-readonly-token";

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Test
    void eachRoleCanDoItsOwnJobAndNothingElse() {
        TestMerchant merchant = createMerchant(ALPHA);
        String merchantPath = "/admin/v1/merchants/" + merchant.id();

        assertThat(as(READ_ONLY, "GET", merchantPath, null).status()).isEqualTo(200);
        assertThat(as(READ_ONLY, "GET", "/admin/v1/reviews", null).status()).isEqualTo(200);
        Response denied = as(READ_ONLY, "POST", merchantPath + "/suspend", Map.of("reason", "test"));
        assertThat(denied.status()).isEqualTo(403);
        assertThat(str(denied.body(), "code")).isEqualTo("forbidden");
        assertThat(str(denied.body(), "detail")).contains("merchants_suspend");

        Response suspended = as(OPS, "POST", merchantPath + "/suspend", Map.of("reason", "chargeback spike"));
        assertThat(suspended.status()).as(suspended.raw()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT actor_id FROM audit_log WHERE action = 'merchant.suspended'").query(String.class).single())
                .as("the operator's name is the audit actor").isEqualTo("ops-asha");
        assertThat(as(OPS, "POST", merchantPath + "/reactivate", null).status()).isEqualTo(200);
        assertThat(as(OPS, "POST", merchantPath + "/api-keys", null).status()).isEqualTo(403);
        assertThat(as(OPS, "POST", "/admin/v1/routing-rules", Map.of()).status()).isEqualTo(403);
        assertThat(as(OPS, "POST", "/admin/v1/webhook-deliveries/whd_missing/replay", null).status())
                .as("allowed, then not found").isEqualTo(404);

        Response run = as(FINANCE, "POST", "/admin/v1/reconciliation/runs", Map.of("merchant_id", merchant.id(),
                "provider", ALPHA, "from", clock.instant().minus(Duration.ofHours(1)).toString(),
                "to", clock.instant().plus(Duration.ofHours(1)).toString()));
        assertThat(run.status()).as(run.raw()).isEqualTo(201);
        assertThat(as(FINANCE, "POST", "/admin/v1/webhook-deliveries/whd_missing/replay", null).status()).isEqualTo(403);
        assertThat(as(FINANCE, "POST", merchantPath + "/suspend", Map.of("reason", "x")).status()).isEqualTo(403);

        assertThat(as("not-a-configured-token", "GET", merchantPath, null).status()).isEqualTo(401);
        assertThat(count("SELECT count(*) FROM merchants WHERE status = 'ACTIVE'")).isEqualTo(1);
    }

    @Test
    void everyAdminWriteEndpointDeclaresItsPermission() {
        List<String> undeclared = new ArrayList<>();
        mappings.getHandlerMethods().forEach((info, handler) -> {
            boolean admin = info.getPatternValues().stream().anyMatch(pattern -> pattern.startsWith("/admin/"));
            for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                if (admin && AdminPermissionInterceptor.requiredPermission(handler, method.name()) == null) {
                    undeclared.add(method + " " + info.getPatternValues());
                }
            }
            if (admin && info.getMethodsCondition().getMethods().isEmpty()) {
                undeclared.add("ANY " + info.getPatternValues());
            }
        });
        assertThat(undeclared).as("admin write endpoints without @RequiresAdmin are denied at runtime").isEmpty();
    }

    private Response as(String token, String method, String path, Object body) {
        return send(method, path, Map.of("Authorization", "Bearer " + token), body);
    }
}
