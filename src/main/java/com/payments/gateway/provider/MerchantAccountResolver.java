package com.payments.gateway.provider;

import com.payments.gateway.provider.spi.MerchantAccount;
import java.util.Optional;

/** Supplies merchants' PSP accounts with decrypted credentials; implemented by the merchant module. */
public interface MerchantAccountResolver {

    /** Resolves regardless of account status: work already in flight on a disabled account must still finish. */
    MerchantAccount require(String merchantId, String providerCode);

    /** For webhooks received on an account's own endpoint. */
    Optional<MerchantAccount> findById(String accountId);
}
