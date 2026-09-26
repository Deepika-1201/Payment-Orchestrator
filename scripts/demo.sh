#!/usr/bin/env bash
# End-to-end demo against a running gateway (./gradlew bootTestRun or docker compose up).
set -euo pipefail

BASE="${BASE_URL:-http://localhost:8080}"
ADMIN="${ADMIN_TOKEN:-local-admin-token}"

field() {
  python3 -c 'import json, sys
data = json.load(sys.stdin)
for key in sys.argv[1].split("."):
    data = data[key] if isinstance(data, dict) else data[int(key)]
print(data)' "$1"
}

post() { # path, auth, idempotency-key, body
  curl -sS -X POST "$BASE$1" -H "Authorization: Bearer $2" -H "Idempotency-Key: $3" \
       -H "Content-Type: application/json" -d "$4"
}

step() { printf '\n\033[1m== %s\033[0m\n' "$1"; }

step "1. Onboard a merchant linked to two mock PSPs"
MERCHANT=$(curl -sS -X POST "$BASE/admin/v1/merchants" -H "Authorization: Bearer $ADMIN" \
  -H "Content-Type: application/json" \
  -d '{"name":"Demo Store","providers":["MOCK_ALPHA","MOCK_BETA"]}')
MERCHANT_ID=$(echo "$MERCHANT" | field id)
KEY=$(curl -sS -X POST "$BASE/admin/v1/merchants/$MERCHANT_ID/api-keys" -H "Authorization: Bearer $ADMIN" | field api_key)
echo "merchant=$MERCHANT_ID  api_key=${KEY:0:12}..."

step "2. Create a payment of INR 499.00 (idempotent)"
IDEM="demo-$(date +%s)"
BODY='{"amount":49900,"currency":"INR","merchant_order_id":"order_demo_1","customer":{"reference":"cust_1"}}'
PAYMENT=$(post /v1/payments "$KEY" "$IDEM" "$BODY")
PAYMENT_ID=$(echo "$PAYMENT" | field id)
echo "payment=$PAYMENT_ID status=$(echo "$PAYMENT" | field status)"
REPLAY_ID=$(post /v1/payments "$KEY" "$IDEM" "$BODY" | field id)
echo "retry with same Idempotency-Key returned the same payment: $([ "$REPLAY_ID" = "$PAYMENT_ID" ] && echo yes || echo NO)"

step "3. Confirm with UPI Intent"
CONFIRMED=$(post "/v1/payments/$PAYMENT_ID/confirm" "$KEY" "$IDEM-confirm" \
  '{"payment_method":{"type":"upi","upi":{"flow":"intent"}},"client":{"ip":"203.0.113.7"}}')
PROVIDER=$(echo "$CONFIRMED" | field latest_attempt.provider)
REFERENCE=$(echo "$CONFIRMED" | field latest_attempt.provider_reference)
echo "status=$(echo "$CONFIRMED" | field status) provider=$PROVIDER"
echo "next_action.upi_uri=$(echo "$CONFIRMED" | field next_action.upi_uri)"

step "4. Customer approves in their UPI app (simulated PSP sends a signed webhook)"
curl -sS -X POST "$BASE/simulator/$PROVIDER/payments/$REFERENCE/complete" -H "Content-Type: application/json" \
  -d '{"outcome":"success"}' > /dev/null
echo "status=$(curl -sS "$BASE/v1/payments/$PAYMENT_ID" -H "Authorization: Bearer $KEY" | field status)"

step "5. Partial refund of INR 100.00"
REFUND=$(post "/v1/payments/$PAYMENT_ID/refunds" "$KEY" "$IDEM-refund" '{"amount":10000,"reason":"damaged item"}')
echo "refund=$(echo "$REFUND" | field id) status=$(echo "$REFUND" | field status)"
echo "amount_refunded=$(curl -sS "$BASE/v1/payments/$PAYMENT_ID" -H "Authorization: Bearer $KEY" | field amount_refunded)"

step "6. PSP timeout (amount ending in 01): outcome unknown, resolved by the status resolver"
TIMEOUT_ID=$(post /v1/payments "$KEY" "$IDEM-t" '{"amount":20001,"currency":"INR","merchant_order_id":"order_demo_2"}' | field id)
UNKNOWN=$(post "/v1/payments/$TIMEOUT_ID/confirm" "$KEY" "$IDEM-t-confirm" '{"payment_method":{"type":"card"}}')
echo "right after confirm: status=$(echo "$UNKNOWN" | field status) attempt=$(echo "$UNKNOWN" | field latest_attempt.status)"
sleep 7
echo "after status check:  status=$(curl -sS "$BASE/v1/payments/$TIMEOUT_ID" -H "Authorization: Bearer $KEY" | field status)"

step "7. Provider health as seen by the router"
curl -sS "$BASE/admin/v1/providers/health" -H "Authorization: Bearer $ADMIN"
echo
