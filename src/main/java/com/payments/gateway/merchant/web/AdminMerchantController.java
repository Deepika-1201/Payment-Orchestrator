package com.payments.gateway.merchant.web;

import com.payments.gateway.merchant.Merchant;
import com.payments.gateway.merchant.MerchantAdminService;
import com.payments.gateway.merchant.MerchantAdminService.CreatedMerchant;
import com.payments.gateway.merchant.MerchantAdminService.IssuedApiKey;
import com.payments.gateway.merchant.MerchantDirectory;
import com.payments.gateway.merchant.MerchantRepository;
import com.payments.gateway.merchant.MerchantRepository.ApiKeyRow;
import com.payments.gateway.merchant.ProviderAccountService;
import com.payments.gateway.merchant.ProviderAccountService.AccountView;
import com.payments.gateway.merchant.RateLimit;
import com.payments.gateway.shared.web.AdminPermission;
import com.payments.gateway.shared.web.RequiresAdmin;
import com.payments.gateway.shared.web.WireEnums;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Merchant lifecycle: onboarding, settings, suspension, API keys, webhook secret and PSP accounts (FR-M1-M3). */
@RestController
@RequestMapping("/admin/v1/merchants")
public class AdminMerchantController {

    public record CreateMerchantRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2048) String webhookUrl,
            String lateSuccessPolicy,
            @Min(60) @Max(86400) Integer paymentExpirySeconds,
            @Size(max = 10) List<@NotBlank String> providers) {
    }

    /**
     * {@code mandateDebitLimit}: frictionless mandate debit limit in minor units, ₹1 to ₹1,00,000 (ADR-035).
     * {@code bankTransferCredits} ({@code add_up} or {@code exact}) and {@code bankTransferShortAtExpiry}
     * ({@code refund} or {@code accept}): how bank transfer credits pay a payment (ADR-038).
     * {@code internationalCards}: payments in the other currencies the merchant's PSPs charge cards in (ADR-040).
     */
    public record UpdateMerchantRequest(
            @Size(min = 1, max = 200) String name,
            @Size(max = 2048) String webhookUrl,
            String lateSuccessPolicy,
            @Min(60) @Max(86400) Integer paymentExpirySeconds,
            @Min(100) @Max(10_000_000) Long mandateDebitLimit,
            String bankTransferCredits,
            String bankTransferShortAtExpiry,
            Boolean internationalCards) {
    }

    public record SuspendRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record RotateWebhookSecretRequest(@Min(0) @Max(604_800) Integer previousValidForSeconds) {
    }

    public record LinkProviderAccountRequest(
            @Size(max = 20) Map<@NotBlank @Size(max = 64) String, @NotNull @Size(max = 4096) String> credentials) {
    }

    public record MerchantResponse(String id, String name, String status, String statusReason, String webhookUrl,
                                   String lateSuccessPolicy, long paymentExpirySeconds, List<String> providers,
                                   String webhookSecret, Instant createdAt, Long mandateDebitLimit,
                                   String bankTransferCredits, String bankTransferShortAtExpiry,
                                   boolean internationalCards) {
    }

    public record ApiKeyResponse(String id, String apiKey, String hint, String mode, String status, Instant createdAt,
                                 Instant lastUsedAt, Instant revokedAt) {
    }

    public record WebhookSecretResponse(String webhookSecret, Instant previousSecretExpiresAt) {
    }

    public record ProviderAccountResponse(String id, String provider, String status, Map<String, String> credentials,
                                          String webhookPath, Instant credentialsUpdatedAt, Instant createdAt,
                                          Instant disabledAt) {
    }

    public record ListResponse<T>(List<T> data) {
    }

    public record LimitBody(@DecimalMin(value = "0", inclusive = false) @DecimalMax("100000") double perSecond,
                            @Min(1) @Max(1_000_000) int burst) {
    }

    /** Omit {@code read} or {@code write} (or send null) to use the platform default. */
    public record RateLimitsRequest(@Valid LimitBody read, @Valid LimitBody write) {
    }

    public record RateLimitsDefaults(LimitBody read, LimitBody write) {
    }

    public record RateLimitsResponse(LimitBody read, LimitBody write, RateLimitsDefaults defaults) {
    }

    private static final Duration DEFAULT_SECRET_OVERLAP = Duration.ofHours(24);

    private final MerchantAdminService admin;
    private final ProviderAccountService providerAccounts;
    private final MerchantDirectory directory;
    private final RateLimitProperties rateLimitDefaults;

    public AdminMerchantController(MerchantAdminService admin, ProviderAccountService providerAccounts,
                                   MerchantDirectory directory, RateLimitProperties rateLimitDefaults) {
        this.admin = admin;
        this.providerAccounts = providerAccounts;
        this.directory = directory;
        this.rateLimitDefaults = rateLimitDefaults;
    }

    @PostMapping
    @RequiresAdmin(AdminPermission.MERCHANTS_WRITE)
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
        return toResponse(directory.require(id));
    }

    @PatchMapping("/{id}")
    @RequiresAdmin(AdminPermission.MERCHANTS_WRITE)
    public MerchantResponse update(@RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor,
                                   @PathVariable String id, @Valid @RequestBody UpdateMerchantRequest request) {
        Merchant.LateSuccessPolicy policy = request.lateSuccessPolicy() == null ? null
                : WireEnums.parse(Merchant.LateSuccessPolicy.class, request.lateSuccessPolicy(), "late_success_policy");
        Duration expiry = request.paymentExpirySeconds() == null ? null : Duration.ofSeconds(request.paymentExpirySeconds());
        Merchant.TransferCredits credits = request.bankTransferCredits() == null ? null
                : WireEnums.parse(Merchant.TransferCredits.class, request.bankTransferCredits(), "bank_transfer_credits");
        Merchant.TransferShortfall shortfall = request.bankTransferShortAtExpiry() == null ? null
                : WireEnums.parse(Merchant.TransferShortfall.class, request.bankTransferShortAtExpiry(),
                "bank_transfer_short_at_expiry");
        return toResponse(admin.update(id, new MerchantAdminService.SettingsUpdate(request.name(), request.webhookUrl(),
                policy, expiry, request.mandateDebitLimit(), credits, shortfall, request.internationalCards()), actor));
    }

    @DeleteMapping("/{id}/webhook-url")
    @RequiresAdmin(AdminPermission.MERCHANTS_WRITE)
    public MerchantResponse removeWebhookUrl(@RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor,
                                             @PathVariable String id) {
        return toResponse(admin.removeWebhookUrl(id, actor));
    }

    @PostMapping("/{id}/webhook-secret")
    @RequiresAdmin(AdminPermission.MERCHANTS_WRITE)
    public WebhookSecretResponse rotateWebhookSecret(@RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor,
                                                     @PathVariable String id,
                                                     @Valid @RequestBody(required = false) RotateWebhookSecretRequest request) {
        Duration overlap = request == null || request.previousValidForSeconds() == null ? DEFAULT_SECRET_OVERLAP
                : Duration.ofSeconds(request.previousValidForSeconds());
        MerchantAdminService.RotatedSecret rotated = admin.rotateWebhookSecret(id, overlap, actor);
        return new WebhookSecretResponse(rotated.webhookSecret(), rotated.previousSecretExpiresAt());
    }

    @PostMapping("/{id}/suspend")
    @RequiresAdmin(AdminPermission.MERCHANTS_SUSPEND)
    public MerchantResponse suspend(@RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor,
                                    @PathVariable String id, @Valid @RequestBody SuspendRequest request) {
        return toResponse(admin.suspend(id, request.reason(), actor));
    }

    @PostMapping("/{id}/reactivate")
    @RequiresAdmin(AdminPermission.MERCHANTS_SUSPEND)
    public MerchantResponse reactivate(@RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor,
                                       @PathVariable String id) {
        return toResponse(admin.reactivate(id, actor));
    }

    @PostMapping("/{id}/api-keys")
    @RequiresAdmin(AdminPermission.MERCHANTS_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiKeyResponse issueKey(@RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor,
                                   @PathVariable String id) {
        IssuedApiKey key = admin.issueApiKey(id, actor);
        return new ApiKeyResponse(key.id(), key.apiKey(), key.hint(), lower(key.mode()), "active", key.createdAt(), null, null);
    }

    @GetMapping("/{id}/api-keys")
    public ListResponse<ApiKeyResponse> listKeys(@PathVariable String id) {
        return new ListResponse<>(admin.apiKeys(id).stream().map(AdminMerchantController::toResponse).toList());
    }

    @PostMapping("/{id}/api-keys/{keyId}/revoke")
    @RequiresAdmin(AdminPermission.MERCHANTS_WRITE)
    public ApiKeyResponse revokeKey(@RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor,
                                    @PathVariable String id, @PathVariable String keyId) {
        return toResponse(admin.revokeApiKey(id, keyId, actor));
    }

    @GetMapping("/{id}/provider-accounts")
    public ListResponse<ProviderAccountResponse> listProviderAccounts(@PathVariable String id) {
        return new ListResponse<>(providerAccounts.list(id).stream().map(AdminMerchantController::toResponse).toList());
    }

    /** Links or re-enables the account; sent credentials replace the stored ones. */
    @PutMapping("/{id}/provider-accounts/{provider}")
    @RequiresAdmin(AdminPermission.MERCHANTS_WRITE)
    public ProviderAccountResponse linkProviderAccount(@RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor,
                                                       @PathVariable String id, @PathVariable String provider,
                                                       @Valid @RequestBody(required = false) LinkProviderAccountRequest request) {
        return toResponse(providerAccounts.link(id, provider, request == null ? null : request.credentials(), actor));
    }

    @PostMapping("/{id}/provider-accounts/{provider}/disable")
    @RequiresAdmin(AdminPermission.MERCHANTS_WRITE)
    public ProviderAccountResponse disableProviderAccount(@RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor,
                                                          @PathVariable String id, @PathVariable String provider) {
        return toResponse(providerAccounts.disable(id, provider, actor));
    }

    /** The merchant's overrides ({@code null} = platform default) and the defaults they replace. */
    @GetMapping("/{id}/rate-limits")
    public RateLimitsResponse rateLimits(@PathVariable String id) {
        return toResponse(admin.rateLimits(id));
    }

    @PutMapping("/{id}/rate-limits")
    @RequiresAdmin(AdminPermission.MERCHANTS_WRITE)
    public RateLimitsResponse updateRateLimits(@RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor,
                                               @PathVariable String id, @Valid @RequestBody RateLimitsRequest request) {
        return toResponse(admin.updateRateLimits(id, new MerchantRepository.RateLimitOverrides(
                toLimit(request.read()), toLimit(request.write())), actor));
    }

    private RateLimitsResponse toResponse(MerchantRepository.RateLimitOverrides overrides) {
        return new RateLimitsResponse(toBody(overrides.read()), toBody(overrides.write()),
                new RateLimitsDefaults(toBody(rateLimitDefaults.read()), toBody(rateLimitDefaults.write())));
    }

    private static RateLimit toLimit(LimitBody body) {
        return body == null ? null : new RateLimit(body.perSecond(), body.burst());
    }

    private static LimitBody toBody(RateLimit limit) {
        return limit == null ? null : new LimitBody(limit.perSecond(), limit.burst());
    }

    private MerchantResponse toResponse(Merchant merchant) {
        return toResponse(merchant, List.copyOf(directory.activeProviders(merchant.id())), null);
    }

    private static MerchantResponse toResponse(Merchant merchant, List<String> providers, String webhookSecret) {
        return new MerchantResponse(merchant.id(), merchant.name(), WireEnums.wire(merchant.status()),
                merchant.statusReason(), merchant.webhookUrl(), WireEnums.wire(merchant.lateSuccessPolicy()),
                merchant.paymentExpiry().toSeconds(), providers, webhookSecret, merchant.createdAt(),
                merchant.mandateDebitLimit(), WireEnums.wire(merchant.transferCredits()),
                WireEnums.wire(merchant.transferShortfall()), merchant.internationalCards());
    }

    private static ApiKeyResponse toResponse(ApiKeyRow key) {
        return new ApiKeyResponse(key.id(), null, key.hint(), lower(key.mode()), lower(key.status()), key.createdAt(),
                key.lastUsedAt(), key.revokedAt());
    }

    private static ProviderAccountResponse toResponse(AccountView account) {
        return new ProviderAccountResponse(account.id(), account.providerCode(), lower(account.status()),
                account.credentials(), "/v1/webhooks/providers/" + account.providerCode() + "/" + account.id(),
                account.credentialsUpdatedAt(), account.createdAt(), account.disabledAt());
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
