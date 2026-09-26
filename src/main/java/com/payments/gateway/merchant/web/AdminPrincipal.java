package com.payments.gateway.merchant.web;

import com.payments.gateway.shared.web.AdminPermission;
import com.payments.gateway.shared.web.AdminRole;
import java.util.Set;

/** An authenticated admin caller: {@code name} is the audit actor. */
public record AdminPrincipal(String name, Set<AdminRole> roles) {

    public AdminPrincipal {
        roles = Set.copyOf(roles);
    }

    public boolean can(AdminPermission permission) {
        return roles.stream().anyMatch(role -> role.grants(permission));
    }
}
