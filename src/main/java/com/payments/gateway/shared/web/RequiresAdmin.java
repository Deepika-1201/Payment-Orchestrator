package com.payments.gateway.shared.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The permission an admin endpoint requires (ADR-019). {@code GET} endpoints default to {@link AdminPermission#READ};
 * any other admin endpoint without this annotation is denied.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresAdmin {

    AdminPermission value();
}
