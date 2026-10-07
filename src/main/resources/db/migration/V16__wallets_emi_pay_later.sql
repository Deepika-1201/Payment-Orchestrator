-- Wallets, card EMI, cardless EMI and pay later (requirements §8.3, ADR-037).

ALTER TABLE payment_attempts DROP CONSTRAINT payment_attempts_method_type_check;
ALTER TABLE payment_attempts ADD CONSTRAINT payment_attempts_method_type_check
    CHECK (method_type IN ('UPI', 'CARD', 'NETBANKING', 'MANDATE', 'WALLET', 'EMI', 'CARDLESS_EMI', 'PAY_LATER'));

-- The card EMI plan the PSP reported with the card.
ALTER TABLE payment_attempts ADD COLUMN emi_tenure_months integer;
ALTER TABLE payment_attempts ADD COLUMN emi_interest_rate_bps integer;
ALTER TABLE payment_attempts ADD COLUMN emi_issuer text;
ALTER TABLE payment_attempts ADD CONSTRAINT ck_attempts_emi_plan CHECK (
    CASE WHEN emi_tenure_months IS NULL THEN emi_interest_rate_bps IS NULL AND emi_issuer IS NULL
         ELSE coalesce(card_network IS NOT NULL AND emi_tenure_months BETWEEN 1 AND 120
                       AND (emi_interest_rate_bps IS NULL OR emi_interest_rate_bps BETWEEN 0 AND 10000), false)
    END);
