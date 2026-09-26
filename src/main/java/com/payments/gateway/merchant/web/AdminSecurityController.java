package com.payments.gateway.merchant.web;

import com.payments.gateway.merchant.SecretRotationService;
import com.payments.gateway.merchant.SecretRotationService.KeyUsage;
import com.payments.gateway.merchant.SecretRotationService.RotationResult;
import com.payments.gateway.shared.web.AdminPermission;
import com.payments.gateway.shared.web.RequiresAdmin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Data key rotation (ADR-025): which keys stored secrets use, and moving them to the primary key. */
@RestController
@RequestMapping("/admin/v1/security/data-keys")
public class AdminSecurityController {

    private final SecretRotationService rotation;

    public AdminSecurityController(SecretRotationService rotation) {
        this.rotation = rotation;
    }

    @GetMapping
    public KeyUsage usage() {
        return rotation.usage();
    }

    @PostMapping("/re-encrypt")
    @RequiresAdmin(AdminPermission.SECURITY_WRITE)
    public RotationResult reEncrypt(@RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor) {
        return rotation.reEncryptAll(actor);
    }
}
