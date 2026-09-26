package com.payments.gateway.merchant.web;

import com.payments.gateway.merchant.Merchant;
import com.payments.gateway.merchant.MerchantAdminService;
import com.payments.gateway.merchant.MerchantAdminService.CreatedMerchant;
import com.payments.gateway.merchant.MerchantAdminService.IssuedApiKey;
import com.payments.gateway.merchant.MerchantDirectory;
import com.payments.gateway.shared.web.WireEnums;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/v1/merchants")
public class AdminMerchantController {

    public record CreateMerchantRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2048) String webhookUrl,
            String lateSuccessPolicy,
            @Min(60) @Max(86400) Integer paymentExpirySeconds,
            @NotEmpty @Size(max = 10) List<@NotBlank String> providers) {
    }

    public record MerchantResponse(String id, String name, String status, String webhookUrl, String lateSuccessPolicy,
                                   long paymentExpirySeconds, List<String> providers, String webhookSecret,
                                   Instant createdAt) {
    }

    public record ApiKeyResponse(String id, String apiKey, String hint, String mode, Instant createdAt) {
    }

    private final MerchantAdminService admin;
    private final MerchantDirectory directory;

    public AdminMerchantController(MerchantAdminService admin, MerchantDirectory directory) {
        this.admin = admin;
        this.directory = directory;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MerchantResponse create(@RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor,
                                   @Valid @RequestBody CreateMerchantRequest request) {
        Merchant.LateSuccessPolicy policy = request.lateSuccessPolicy() == null
                ? Merchant.LateSuccessPolicy.AUTO_REFUND
                : WireEnums.parse(Merchant.LateSuccessPolicy.class, request.lateSuccessPolicy(), "late_success_policy");
        Duration expiry = Duration.ofSeconds(request.paymentExpirySeconds() == null ? 900 : request.paymentExpirySeconds());
        CreatedMerchant created = admin.create(new MerchantAdminService.CreateMerchantCommand(
                request.name(), request.webhookUrl(), policy, expiry, request.providers()), actor);
        return toResponse(created.merchant(), created.providers(), created.webhookSecret());
    }

    @GetMapping("/{id}")
    public MerchantResponse get(@PathVariable String id) {
        Merchant merchant = directory.require(id);
        return toResponse(merchant, List.copyOf(directory.activeProviders(id)), null);
    }

    @PostMapping("/{id}/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiKeyResponse issueKey(@RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor,
                                   @PathVariable String id) {
        IssuedApiKey key = admin.issueApiKey(id, actor);
        return new ApiKeyResponse(key.id(), key.apiKey(), key.hint(), "test", key.createdAt());
    }

    private static MerchantResponse toResponse(Merchant merchant, List<String> providers, String webhookSecret) {
        return new MerchantResponse(merchant.id(), merchant.name(), WireEnums.wire(merchant.status()),
                merchant.webhookUrl(), WireEnums.wire(merchant.lateSuccessPolicy()),
                merchant.paymentExpiry().toSeconds(), providers, webhookSecret, merchant.createdAt());
    }
}
