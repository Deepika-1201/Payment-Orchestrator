package com.payments.gateway.treasury.web;

import com.payments.gateway.merchant.web.AdminAuthFilter;
import com.payments.gateway.shared.web.AdminPermission;
import com.payments.gateway.shared.web.RequiresAdmin;
import com.payments.gateway.treasury.TreasuryModel;
import com.payments.gateway.treasury.TreasuryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/v1/treasury/exceptions")
public class AdminTreasuryExceptionController {
    public record Resolve(@NotBlank @Size(max = 1000) String reason) {
    }

    private final TreasuryService treasury;

    public AdminTreasuryExceptionController(TreasuryService treasury) {
        this.treasury = treasury;
    }

    @GetMapping
    public Map<String, List<TreasuryModel.ExceptionView>> list(@RequestParam(required = false) String merchantId,
            @RequestParam(defaultValue = "open") String status) {
        return Map.of("data", treasury.exceptions(merchantId, status));
    }

    @PostMapping("/{id}/resolve")
    @RequiresAdmin(AdminPermission.FINANCE_WRITE)
    public TreasuryModel.ExceptionView resolve(@PathVariable String id, @Valid @RequestBody Resolve request,
            @RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor) {
        return treasury.resolveException(id, actor, request.reason());
    }
}