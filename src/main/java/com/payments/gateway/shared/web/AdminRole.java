package com.payments.gateway.shared.web;

import java.util.EnumSet;
import java.util.Set;

/** Admin roles and the permissions they grant (ADR-019). */
public enum AdminRole {
    ADMIN(EnumSet.allOf(AdminPermission.class)),
    OPS(EnumSet.of(AdminPermission.READ, AdminPermission.MERCHANTS_SUSPEND, AdminPermission.OPERATIONS_WRITE)),
    FINANCE(EnumSet.of(AdminPermission.READ, AdminPermission.FINANCE_WRITE)),
    READ_ONLY(EnumSet.of(AdminPermission.READ));

    private final Set<AdminPermission> permissions;

    AdminRole(Set<AdminPermission> permissions) {
        this.permissions = Set.copyOf(permissions);
    }

    public boolean grants(AdminPermission permission) {
        return permissions.contains(permission);
    }
}
