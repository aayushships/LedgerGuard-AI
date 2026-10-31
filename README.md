<<<<<<< HEAD
# LedgerGuard-AI
AI-powered payment reliability, fraud risk assessment, and immutable double-entry ledger system using Spring Boot and PayPal.
=======
# PayPal Ledger

A backend that records PayPal payments in an append-only double-entry ledger, ignores duplicate
webhooks, and (next steps) reconciles against PayPal and flags risky payments.

## Run it

1. Install Java 21 and Maven, and Docker.
2. Start the database: `docker compose up -d`
3. Set your PayPal sandbox keys (developer.paypal.com -> Apps & Credentials -> Sandbox):
   `export PAYPAL_CLIENT_ID=... PAYPAL_CLIENT_SECRET=...`
4. Start the app: `mvn spring-boot:run`
5. Check it: `curl localhost:8080/api/ledger/status` should return `{"balanced":true}`

## Try the duplicate protection (no PayPal needed)

Send the same fake event twice. The second reply should say `duplicate ignored`.

```bash
curl -X POST localhost:8080/webhooks/paypal -H 'Content-Type: application/json' -d '{
  "id": "WH-TEST-1",
  "event_type": "PAYMENT.CAPTURE.COMPLETED",
  "resource": { "id": "CAP-1", "amount": { "value": "25.00", "currency_code": "USD" } }
}'
```

Then try editing the ledger by hand in psql (`UPDATE ledger_entries ...`). The database refuses it.

## What is where

| File | Job |
|---|---|
| `V1__init.sql` | Tables, plus triggers that make the ledger append-only |
| `LedgerService` | Posts balanced debit/credit entries; refuses unbalanced ones |
| `WebhookController` | Receives PayPal events once, posts to the ledger |

## Next steps

1. Verify webhook signatures with PayPal's `verify-webhook-signature` API.
2. Create a sandbox order and capture it, using the `PayPal-Request-Id` header.
3. Register your webhook URL in the PayPal dashboard (use ngrok locally) and set `PAYPAL_WEBHOOK_ID`.
4. Reconciliation job comparing the ledger with PayPal's records.
5. Risk scoring rules, then an LLM-written explanation of why a payment was flagged.
6. Tests for: duplicate webhook, unbalanced post, refund reversal.
>>>>>>> 67da0ca (Initial commit: LedgerGuard AI)
