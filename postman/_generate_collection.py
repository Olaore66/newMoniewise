#!/usr/bin/env python3
"""Generate Postman Collection v2.1 + environment from this backend's controllers."""
from __future__ import annotations

import json
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def uid() -> str:
    return str(uuid.uuid4())


def url(path: str, query: list | None = None) -> dict:
    raw = "{{baseUrl}}" + path
    segs = [s for s in path.split("/") if s]
    parts = {
        "raw": raw if not query else raw + "?" + "&".join(f"{q['key']}={q.get('value','')}" for q in query),
        "host": ["{{baseUrl}}"],
        "path": segs,
    }
    if query:
        parts["query"] = query
        parts["raw"] = "{{baseUrl}}" + path + "?" + "&".join(
            f"{q['key']}={q.get('value','')}" for q in query if q.get("disabled") is not True
        )
    return parts


def hdrs(*extra: dict, json_body: bool = False) -> list:
    h = [{"key": "Accept", "value": "application/json"}]
    if json_body:
        h.append({"key": "Content-Type", "value": "application/json"})
    h.extend(extra)
    return h


def body_json(obj) -> dict:
    return {"mode": "raw", "raw": json.dumps(obj, indent=2), "options": {"raw": {"language": "json"}}}


NOAUTH = {"type": "noauth"}

SAVE_TOKEN = """
if (pm.response.code === 200 || pm.response.code === 201) {
  try {
    const j = pm.response.json();
    if (j.token) {
      pm.collectionVariables.set("accessToken", j.token);
      pm.environment.set("accessToken", j.token);
    }
  } catch (e) {}
}
""".strip()

SAVE_THREAD = """
if (pm.response.code === 200) {
  try {
    const j = pm.response.json();
    if (j.threadId) pm.collectionVariables.set("threadId", j.threadId);
    if (j.id) pm.collectionVariables.set("threadId", j.id);
  } catch (e) {}
}
""".strip()

SAVE_ACTION = """
if (pm.response.code === 200) {
  try {
    const j = pm.response.json();
    const frames = j.frames || [];
    for (const f of frames) {
      const payload = f.payload || {};
      const id = payload.actionId || payload.id;
      if (id && (f.type === "ACTION_PROPOSED" || payload.kind)) {
        pm.collectionVariables.set("actionId", id);
        break;
      }
    }
  } catch (e) {}
}
""".strip()


def req(
    name: str,
    method: str,
    path: str,
    *,
    desc: str = "",
    query: list | None = None,
    json_body=None,
    formdata: list | None = None,
    auth: dict | None = None,
    extra_headers: list | None = None,
    tests: str | None = None,
    public: bool = False,
) -> dict:
    item: dict = {
        "name": name,
        "request": {
            "method": method,
            "header": hdrs(*(extra_headers or []), json_body=json_body is not None),
            "url": url(path, query),
            "description": desc,
        },
        "response": [],
    }
    if public:
        item["request"]["auth"] = NOAUTH
    elif auth:
        item["request"]["auth"] = auth
    if json_body is not None:
        item["request"]["body"] = body_json(json_body)
    if formdata is not None:
        item["request"]["header"] = hdrs(*(extra_headers or []))
        item["request"]["body"] = {"mode": "formdata", "formdata": formdata}
    if tests:
        item["event"] = [{"listen": "test", "script": {"type": "text/javascript", "exec": tests.split("\n")}}]
    return item


def folder(name: str, desc: str, items: list) -> dict:
    return {"name": name, "description": desc, "item": items}


def main() -> None:
    items = [
        folder(
            "00 Public — no JWT",
            "World-readable surfaces. Auth is disabled on these requests.",
            [
                req("App config", "GET", "/app/config", public=True, desc="Budget minimum and currency for the client."),
                req(
                    "App version check",
                    "GET",
                    "/app/version-check",
                    public=True,
                    query=[
                        {"key": "platform", "value": "android"},
                        {"key": "version", "value": "1.0.0"},
                        {"key": "build", "value": "1"},
                    ],
                    extra_headers=[
                        {"key": "X-App-Platform", "value": "android"},
                        {"key": "X-App-Version", "value": "1.0.0"},
                        {"key": "X-App-Build", "value": "1"},
                    ],
                    desc="Also available as GET /app/update-status.",
                ),
                req("App update status (alias)", "GET", "/app/update-status", public=True),
                req("Terms (legacy /tnc)", "GET", "/tnc", public=True),
                req("HTML privacy policy", "GET", "/privacy-policy", public=True),
                req("HTML terms of use", "GET", "/terms-of-use", public=True),
                req("HTML delete-account page", "GET", "/delete-account", public=True),
                req("Blog posts (JSON)", "GET", "/blog/api/posts", public=True, query=[{"key": "page", "value": "0"}, {"key": "size", "value": "20"}]),
                req("Blog post by slug (JSON)", "GET", "/blog/api/posts/{{blogSlug}}", public=True),
                req("Blog index (HTML)", "GET", "/blog", public=True, query=[{"key": "page", "value": "0"}, {"key": "size", "value": "10"}]),
                req("Blog article (HTML)", "GET", "/blog/{{blogSlug}}", public=True),
                req("Android Digital Asset Links", "GET", "/.well-known/assetlinks.json", public=True),
                req("Apple App Site Association", "GET", "/.well-known/apple-app-site-association", public=True),
                req("Apple AASA (root alias)", "GET", "/apple-app-site-association", public=True),
                req("Deep-link fallback HTML", "GET", "/open/budgets", public=True),
            ],
        ),
        folder(
            "01 Auth",
            "Signup is Redis+OTP then JWT. Login may return 403 OTP verification required unless X-Device-Id is trusted or the account is a test reviewer account.",
            [
                req(
                    "Signup",
                    "POST",
                    "/auth/signup",
                    public=True,
                    json_body={"email": "{{email}}", "phone": "{{phone}}", "password": "{{password}}", "role": "USER"},
                    desc="201 — OTP emailed. No user row yet.",
                ),
                req("Resend signup OTP", "POST", "/auth/resend-signup-otp", public=True, json_body={"email": "{{email}}"}),
                req(
                    "Verify signup OTP",
                    "POST",
                    "/auth/verify-signup-otp",
                    public=True,
                    json_body={"email": "{{email}}", "otp": "{{otp}}"},
                    tests=SAVE_TOKEN,
                    desc="Creates the user and returns JWT.",
                ),
                req(
                    "BVN pre-verify (signup)",
                    "POST",
                    "/auth/bvn/pre-verify",
                    public=True,
                    json_body={
                        "phone": "{{phone}}",
                        "bvn": "{{bvn}}",
                        "firstName": "Ada",
                        "lastName": "Okafor",
                        "dob": "1995-03-12",
                    },
                ),
                req(
                    "Login",
                    "POST",
                    "/auth/login",
                    public=True,
                    json_body={"emailOrPhone": "{{email}}", "password": "{{password}}"},
                    extra_headers=[{"key": "X-Device-Id", "value": "{{deviceId}}"}],
                    tests=SAVE_TOKEN,
                    desc="Saves token to collection + environment. Send a stable X-Device-Id after OTP so this device is trusted.",
                ),
                req("Logout", "POST", "/auth/logout"),
                req("Refresh session", "POST", "/auth/refresh", tests=SAVE_TOKEN),
                req("Forgot password", "POST", "/auth/forgot-password", public=True, json_body={"email": "{{email}}"}),
                req(
                    "Verify reset OTP",
                    "POST",
                    "/auth/verify-reset-otp",
                    public=True,
                    json_body={"email": "{{email}}", "otp": "{{otp}}"},
                ),
                req(
                    "Reset password",
                    "POST",
                    "/auth/reset-password",
                    public=True,
                    json_body={"email": "{{email}}", "token": "{{otp}}", "password": "{{password}}"},
                ),
                req(
                    "Change password",
                    "POST",
                    "/auth/change-password",
                    json_body={
                        "currentPassword": "{{password}}",
                        "newPassword": "NewPass123!",
                        "confirmNewPassword": "NewPass123!",
                    },
                ),
                req("Google login", "POST", "/auth/google", public=True, json_body={"token": "{{googleIdToken}}"}, tests=SAVE_TOKEN),
                req(
                    "Delete account",
                    "DELETE",
                    "/auth/delete",
                    json_body={"reason": "testing", "transactionPin": "{{pin}}"},
                    desc="May return status=withdrawal_required if wallet still has funds.",
                ),
                req("Finalize account deletion", "POST", "/auth/delete/finalize"),
                req("Generate login OTP", "POST", "/users/otp/generate", public=True, json_body={"emailOrPhone": "{{email}}"}),
                req(
                    "Verify login OTP",
                    "POST",
                    "/users/otp/verify",
                    public=True,
                    json_body={"emailOrPhone": "{{email}}", "otpCode": "{{otp}}"},
                    extra_headers=[{"key": "X-Device-Id", "value": "{{deviceId}}"}, {"key": "X-Device-Name", "value": "Postman"}],
                    tests=SAVE_TOKEN,
                ),
            ],
        ),
        folder(
            "02 Users & profile",
            "JWT required except OTP endpoints above.",
            [
                req("Me", "GET", "/users/me"),
                req(
                    "Update profile",
                    "POST",
                    "/users/profile",
                    json_body={
                        "firstName": "Ada",
                        "lastName": "Okafor",
                        "phone": "{{phone}}",
                        "bvn": "{{bvn}}",
                        "gender": "FEMALE",
                        "monthlyIncome": 350000,
                        "mainExpense": "Rent",
                        "savingsGoal": "Emergency fund",
                        "occupation": "Engineer",
                        "referralSource": "friend",
                        "dob": "1995-03-12",
                    },
                ),
                req("Upload profile image", "POST", "/users/image", formdata=[{"key": "file", "type": "file", "src": []}]),
                req("Get profile image URL", "GET", "/users/image"),
                req("Delete profile image", "DELETE", "/users/image"),
                req("Accept TnC (users)", "PATCH", "/users/tnc", json_body={"accepted": True}),
                req(
                    "Search users (P2P)",
                    "GET",
                    "/users/search",
                    query=[{"key": "query", "value": "ada"}, {"key": "provider", "value": "", "disabled": True}],
                ),
                req("Register FCM token", "POST", "/users/fcm-token", json_body={"token": "{{fcmToken}}", "platform": "android"}),
                req("Delete FCM token", "DELETE", "/users/fcm-token", json_body={"token": "{{fcmToken}}"}),
                req("Delete device token (alias)", "DELETE", "/users/device-token", json_body={"token": "{{fcmToken}}"}),
                req("Recent budgets", "GET", "/users/budgets/recent"),
            ],
        ),
        folder(
            "03 KYC",
            "",
            [
                req("KYC status", "GET", "/api/kyc/status"),
                req(
                    "Create/update KYC profile",
                    "POST",
                    "/api/kyc/profile",
                    json_body={"bvn": "{{bvn}}", "sourceOfFunds": "SALARY", "sourceOfWealth": "EMPLOYMENT"},
                ),
                req("Verify BVN (SecureWave)", "POST", "/api/kyc/bvn/verify", json_body={"bvn": "{{bvn}}"}),
            ],
        ),
        folder(
            "04 Transaction PIN",
            "4-digit PIN. Rate-limited. Needed for withdrawals, VAS, envelope transfers, AI confirm.",
            [
                req("PIN status", "GET", "/transactions/pin/status"),
                req("Create PIN", "POST", "/transactions/pin/create", json_body={"pin": "{{pin}}", "confirmPin": "{{pin}}"}),
                req("Verify PIN", "POST", "/transactions/pin/verify", json_body={"pin": "{{pin}}"}),
                req("Change PIN", "POST", "/transactions/pin/change", json_body={"currentPin": "{{pin}}", "newPin": "2468"}),
                req("Forgot PIN — request OTP", "POST", "/transactions/pin/forgot/request"),
                req("Forgot PIN — verify OTP", "POST", "/transactions/pin/forgot/verify", json_body={"otp": "{{otp}}"}),
                req(
                    "Forgot PIN — reset",
                    "POST",
                    "/transactions/pin/forgot/reset",
                    json_body={"otp": "{{otp}}", "newPin": "{{pin}}", "confirmNewPin": "{{pin}}"},
                ),
            ],
        ),
        folder(
            "05 Wallet",
            "",
            [
                req("Get wallet", "GET", "/wallets"),
                req("Linked settlement bank", "GET", "/wallets/bank-info"),
                req("Supported banks", "GET", "/wallets/banks"),
                req("Recent recipients", "GET", "/wallets/recent-recipients", query=[{"key": "limit", "value": "10"}]),
                req(
                    "Resolve account name",
                    "POST",
                    "/wallets/resolve-account",
                    json_body={"bankCode": "058", "accountNumber": "{{nuban}}"},
                ),
                req("Detect banks for NUBAN", "POST", "/wallets/detect-banks", json_body={"accountNumber": "{{nuban}}"}),
                req(
                    "Save settlement bank",
                    "POST",
                    "/wallets/bank-info",
                    json_body={"bankCode": "058", "bankName": "GTBank", "accountNumber": "{{nuban}}"},
                ),
                req("Withdraw quote", "POST", "/wallets/withdraw/quote", json_body={"amount": 5000, "closure": False}),
                req("Fee preview", "GET", "/wallets/transfer/fee-preview", query=[{"key": "amount", "value": "10000"}]),
                req(
                    "Withdraw to bank",
                    "POST",
                    "/wallets/withdraw",
                    json_body={
                        "amount": 5000,
                        "transactionPin": "{{pin}}",
                        "narration": "Wallet withdrawal",
                        "bankCode": "058",
                        "bankName": "GTBank",
                        "accountNumber": "{{nuban}}",
                        "accountName": "ADA OKAFOR",
                        "closure": False,
                    },
                ),
            ],
        ),
        folder(
            "06 Budgets",
            "DELETE /budgets/{id} is ADMIN only.",
            [
                req(
                    "Create budget",
                    "POST",
                    "/budgets",
                    json_body={
                        "name": "September",
                        "totalAmount": 100000,
                        "durationDays": 30,
                        "startDate": "2026-09-01",
                        "endDate": "2026-09-30",
                        "status": "DRAFT",
                        "termsAccepted": True,
                        "envelopes": [
                            {
                                "name": "Food",
                                "percentage": 40,
                                "exactAmount": 40000,
                                "conditions": {"type": "daily", "limit": 2000},
                            },
                            {"name": "Transport", "percentage": 20, "exactAmount": 20000, "conditions": {"type": "none"}},
                            {"name": "Savings", "percentage": 40, "exactAmount": 40000, "conditions": {"type": "none"}},
                        ],
                    },
                ),
                req("List budgets", "GET", "/budgets"),
                req("Dashboard", "GET", "/budgets/dashboard"),
                req("Get budget", "GET", "/budgets/{{budgetId}}"),
                req("Budget envelopes", "GET", "/budgets/{{budgetId}}/envelopes"),
                req("Completion analytics", "GET", "/budgets/{{budgetId}}/completion-analytics"),
                req("Activate budget", "PATCH", "/budgets/{{budgetId}}/activate"),
                req("Cancel scheduled budget", "POST", "/budgets/{{budgetId}}/cancel-scheduled"),
                req("Top up budget", "POST", "/budgets/{{budgetId}}/topup", json_body={"amount": 10000}),
                req(
                    "Extend budget",
                    "POST",
                    "/budgets/{{budgetId}}/extend",
                    json_body={"new_name": "October", "new_end_date": "2026-10-31"},
                ),
                req(
                    "Lock envelope",
                    "POST",
                    "/budgets/envelopes/lock",
                    json_body={"envelopeId": "{{envelopeId}}", "lockType": "SAFE_LOCK", "durationDays": 30, "interestRate": 0},
                ),
                req("Budgets for user (self/admin)", "GET", "/budgets/user/{{userId}}"),
                req("Delete budget (ADMIN)", "DELETE", "/budgets/{{budgetId}}"),
            ],
        ),
        folder(
            "07 Budget templates",
            "",
            [
                req(
                    "Save template",
                    "POST",
                    "/budget-templates",
                    json_body={
                        "name": "Salary split",
                        "envelopes": [
                            {"name": "Needs", "percentage": 50, "conditions": {"type": "none"}},
                            {"name": "Wants", "percentage": 30, "conditions": {"type": "none"}},
                            {"name": "Save", "percentage": 20, "conditions": {"type": "none"}},
                        ],
                    },
                ),
                req("List templates", "GET", "/budget-templates"),
                req("Get template", "GET", "/budget-templates/{{templateId}}"),
                req(
                    "Update template",
                    "PUT",
                    "/budget-templates/{{templateId}}",
                    json_body={
                        "name": "Salary split v2",
                        "envelopes": [{"name": "Needs", "percentage": 55, "conditions": {"type": "none"}}],
                    },
                ),
                req("Delete template", "DELETE", "/budget-templates/{{templateId}}"),
                req(
                    "Create budget from template",
                    "POST",
                    "/budget-templates/{{templateId}}/create-budget",
                    json_body={
                        "name": "From template",
                        "totalAmount": 100000,
                        "durationDays": 30,
                        "startDate": "2026-09-01",
                        "endDate": "2026-09-30",
                    },
                ),
            ],
        ),
        folder(
            "08 Envelopes",
            "",
            [
                req(
                    "Create envelope",
                    "POST",
                    "/envelopes",
                    json_body={
                        "name": "Treats",
                        "budgetId": "{{budgetId}}",
                        "percentage": 10,
                        "exactAmount": 5000,
                        "conditions": {"type": "daily", "limit": 500},
                    },
                ),
                req("Get envelope", "GET", "/envelopes/{{envelopeId}}"),
                req(
                    "Update envelope conditions",
                    "PUT",
                    "/envelopes/{{envelopeId}}",
                    json_body={"name": "Treats", "conditions": {"type": "weekly", "limit": 3000}},
                ),
                req("Delete envelope", "DELETE", "/envelopes/{{envelopeId}}"),
                req(
                    "Move between envelopes",
                    "POST",
                    "/envelopes/{{envelopeId}}/move",
                    json_body={"target_envelope_id": 2, "amount": 1000, "withdrawalReason": "rebalance"},
                ),
                req(
                    "External transfer (path)",
                    "POST",
                    "/envelopes/{{envelopeId}}/transfer-external",
                    json_body={
                        "amount": 2000,
                        "transactionPin": "{{pin}}",
                        "withdrawalReason": "rent",
                        "narration": "Rent",
                        "externalAccount": {
                            "accountNumber": "{{nuban}}",
                            "bankCode": "058",
                            "bankName": "GTBank",
                            "recipientName": "ADA OKAFOR",
                        },
                    },
                ),
                req(
                    "External transfer (body alias)",
                    "POST",
                    "/envelopes/transfer/external",
                    json_body={
                        "sourceEnvelopeId": "{{envelopeId}}",
                        "amount": 2000,
                        "accountNumber": "{{nuban}}",
                        "bankCode": "058",
                        "bankName": "GTBank",
                        "recipientName": "ADA OKAFOR",
                        "narration": "Rent",
                        "withdrawalReason": "rent",
                        "transactionPin": "{{pin}}",
                    },
                ),
                req(
                    "Quote external transfer",
                    "POST",
                    "/envelopes/{{envelopeId}}/transfer-external/quote",
                    json_body={"amount": 2000, "note": "preview"},
                ),
                req(
                    "P2P transfer",
                    "POST",
                    "/envelopes/transfer/p2p",
                    json_body={
                        "sourceEnvelopeId": "{{envelopeId}}",
                        "recipientIdentity": "friend@example.com",
                        "amount": 1500,
                        "note": "lunch",
                        "withdrawalReason": "gift",
                        "transactionPin": "{{pin}}",
                    },
                ),
                req("Pending disbursement", "GET", "/envelopes/{{envelopeId}}/disbursement/pending"),
                req("Claim envelope disbursement", "POST", "/envelopes/disbursements/{{disbursementId}}/claim"),
                req(
                    "Setup auto-transfer",
                    "POST",
                    "/envelopes/{{envelopeId}}/auto-transfer",
                    json_body={
                        "bankCode": "058",
                        "bankName": "GTBank",
                        "accountNumber": "{{nuban}}",
                        "accountName": "ADA OKAFOR",
                        "transactionPin": "{{pin}}",
                    },
                ),
                req("Auto-transfer status", "GET", "/envelopes/{{envelopeId}}/auto-transfer"),
                req("Disable auto-transfer", "DELETE", "/envelopes/{{envelopeId}}/auto-transfer"),
            ],
        ),
        folder(
            "09 Savings",
            "",
            [
                req(
                    "Create savings goal",
                    "POST",
                    "/savings",
                    json_body={
                        "name": "Laptop",
                        "targetAmount": 250000,
                        "initialDeposit": 10000,
                        "maturityDate": "2027-03-01",
                        "interestRate": 0,
                    },
                ),
                req("List savings", "GET", "/savings"),
                req("Active savings", "GET", "/savings/active"),
                req("Fund savings", "POST", "/savings/{{savingsId}}/fund", json_body={"amount": 5000}),
                req("Withdraw savings (full or partial)", "POST", "/savings/{{savingsId}}/withdraw", json_body={"amount": 5000}),
                req(
                    "Savings → bank",
                    "POST",
                    "/savings/{{savingsId}}/transfer/bank",
                    json_body={
                        "amount": 5000,
                        "transactionPin": "{{pin}}",
                        "bankCode": "058",
                        "bankName": "GTBank",
                        "accountNumber": "{{nuban}}",
                        "accountName": "ADA OKAFOR",
                    },
                ),
                req(
                    "Savings → P2P",
                    "POST",
                    "/savings/{{savingsId}}/transfer/p2p",
                    json_body={
                        "recipientIdentity": "friend@example.com",
                        "amount": 2000,
                        "note": "split",
                        "transactionPin": "{{pin}}",
                    },
                ),
                req("Sweep envelope into savings", "POST", "/savings/sweep-envelope/{{envelopeId}}"),
                req("Quote savings bank transfer", "GET", "/savings/quote-bank-transfer", query=[{"key": "amount", "value": "5000"}]),
            ],
        ),
        folder(
            "10 Transactions",
            "POST /transactions/transfer uses query params, not JSON.",
            [
                req(
                    "List transactions",
                    "GET",
                    "/transactions",
                    query=[
                        {"key": "page", "value": "0"},
                        {"key": "size", "value": "30"},
                        {"key": "envelopeId", "value": "{{envelopeId}}", "disabled": True},
                    ],
                ),
                req(
                    "Month totals",
                    "GET",
                    "/transactions/month-totals",
                    query=[{"key": "year", "value": "2026"}, {"key": "month", "value": "9"}],
                ),
                req("Provider transactions", "GET", "/transactions/provider", query=[{"key": "page", "value": "1"}, {"key": "perPage", "value": "20"}]),
                req("Provider transaction by ref", "GET", "/transactions/provider/{{providerRef}}"),
                req("Transaction detail", "GET", "/transactions/{{transactionId}}"),
                req(
                    "Initiate withdrawal",
                    "POST",
                    "/transactions/withdraw",
                    json_body={
                        "amount": 5000,
                        "transactionPin": "{{pin}}",
                        "bankCode": "058",
                        "bankName": "GTBank",
                        "accountNumber": "{{nuban}}",
                        "accountName": "ADA OKAFOR",
                    },
                ),
                req(
                    "Initiate envelope transfer",
                    "POST",
                    "/transactions/transfer",
                    query=[
                        {"key": "amount", "value": "1000"},
                        {"key": "sourceEnvelopeId", "value": "{{envelopeId}}"},
                        {"key": "destinationReference", "value": "friend@example.com"},
                    ],
                ),
                req("Transaction decision", "GET", "/transactions/decision/{{transactionRequestId}}"),
            ],
        ),
        folder(
            "11 Beneficiaries, disbursements, notifications, badges",
            "",
            [
                req("Add beneficiary", "POST", "/beneficiaries", json_body={"email": "friend@example.com", "alias": "Chioma"}),
                req("List beneficiaries", "GET", "/beneficiaries"),
                req("Claim disbursement", "POST", "/disbursements/{{disbursementId}}/claim"),
                req("Notifications inbox", "GET", "/notifications", query=[{"key": "page", "value": "0"}, {"key": "size", "value": "20"}]),
                req("Unread notifications", "GET", "/notifications/unread"),
                req("Unread count", "GET", "/notifications/unread-count"),
                req("Notifications by type", "GET", "/notifications/type/DISBURSEMENT_SUCCESS"),
                req("Mark notification read", "PUT", "/notifications/{{notificationId}}/read"),
                req("Mark all read", "PUT", "/notifications/read-all"),
                req("My badges", "GET", "/badges/me", query=[{"key": "unseenOnly", "value": "false"}]),
                req("Mark badge seen", "PATCH", "/badges/me/{{userBadgeId}}/seen"),
                req("Badge share card SVG", "GET", "/badges/me/{{userBadgeId}}/share-card.svg"),
            ],
        ),
        folder(
            "12 VAS (airtime & data)",
            "PIN-gated. Funded from an envelope.",
            [
                req("Data plans", "GET", "/vas/data/plans", query=[{"key": "networkId", "value": "1", "disabled": True}]),
                req(
                    "Buy airtime",
                    "POST",
                    "/vas/airtime/purchase",
                    json_body={
                        "network": "MTN",
                        "mobileNumber": "{{phone}}",
                        "amount": 200,
                        "envelopeId": "{{envelopeId}}",
                        "transactionPin": "{{pin}}",
                    },
                ),
                req(
                    "Buy data",
                    "POST",
                    "/vas/data/purchase",
                    json_body={
                        "dataId": "{{dataPlanId}}",
                        "mobileNumber": "{{phone}}",
                        "envelopeId": "{{envelopeId}}",
                        "transactionPin": "{{pin}}",
                    },
                ),
                req("VAS history", "GET", "/vas/transactions"),
            ],
        ),
        folder(
            "13 Subscriptions",
            "",
            [
                req("Plans", "GET", "/subscriptions/plans"),
                req("Status", "GET", "/subscriptions/status"),
                req("Subscribe", "POST", "/subscriptions/subscribe", json_body={"planName": "PREMIUM", "paymentReference": "PST-xxxxxxxxxxxxx"}),
                req("Cancel", "POST", "/subscriptions/cancel"),
            ],
        ),
        folder(
            "14 Legal (in-app JSON)",
            "/legal/** requires JWT. HTML pages are in Public.",
            [
                req("Privacy policy JSON", "GET", "/legal/privacy-policy"),
                req("Terms JSON", "GET", "/legal/terms"),
                req("Acceptance status", "GET", "/legal/acceptance-status", query=[{"key": "docType", "value": "PRIVACY_POLICY"}]),
                req(
                    "Accept document",
                    "POST",
                    "/legal/accept",
                    json_body={"docType": "PRIVACY_POLICY", "legalDocumentId": 1},
                ),
                req("Accept TnC (legacy)", "PATCH", "/tnc", json_body={"accepted": True}),
                req(
                    "Publish privacy policy (ADMIN)",
                    "PUT",
                    "/legal/privacy-policy",
                    json_body={"content": "# Privacy Policy\\n...", "version": "2.0"},
                ),
                req(
                    "Publish terms (ADMIN)",
                    "PUT",
                    "/legal/terms",
                    json_body={"content": "# Terms\\n...", "version": "2.0"},
                ),
            ],
        ),
        folder(
            "15 Gemini AI (legacy /ai)",
            "Separate from in-process Monnie SDK. Volume-limited (20/hour except dashboard).",
            [
                req(
                    "Starter envelopes",
                    "POST",
                    "/ai/starter-envelopes",
                    json_body={
                        "totalBudget": 100000,
                        "durationDays": 30,
                        "goal": "Survive until payday",
                        "currency": "NGN",
                        "interpretUserPlan": False,
                    },
                ),
                req(
                    "Budget allocation",
                    "POST",
                    "/ai/budget-allocation",
                    json_body={"totalBudget": 100000, "durationDays": 30, "goal": "Save 20%", "currency": "NGN"},
                ),
                req(
                    "Budget assistant turn",
                    "POST",
                    "/ai/budget-assistant/turn",
                    json_body={
                        "budgetName": "September",
                        "totalBudget": 100000,
                        "durationDays": 30,
                        "goal": "Cut eating out",
                        "currency": "NGN",
                        "latestUserMessage": "Put more in food",
                        "envelopes": [],
                        "messages": [{"role": "user", "content": "Put more in food"}],
                    },
                ),
                req("Dashboard next action", "GET", "/ai/dashboard-next-action"),
                req("Gemini status", "GET", "/ai/gemini-status"),
            ],
        ),
        folder(
            "16 Monnie SDK chat (/ai/sdk)",
            "Session JWT. Wrong-user thread/action → 404. Instructions persist on the thread; later turns cannot swap them. PIN confirm does not execute from the SDK itself.",
            [
                req(
                    "Create thread",
                    "POST",
                    "/ai/sdk/threads",
                    json_body={
                        "surface": "ONBOARDING",
                        "title": "First budget",
                        "instructions": "Speak simply. Ask one question at a time.",
                    },
                    tests=SAVE_THREAD,
                ),
                req(
                    "List threads",
                    "GET",
                    "/ai/sdk/threads",
                    query=[
                        {"key": "surface", "value": "CHAT", "disabled": True},
                        {"key": "before", "value": "2026-12-31T23:59:59Z", "disabled": True},
                        {"key": "limit", "value": "20"},
                    ],
                ),
                req("Get thread", "GET", "/ai/sdk/threads/{{threadId}}"),
                req(
                    "Patch thread",
                    "PATCH",
                    "/ai/sdk/threads/{{threadId}}",
                    json_body={"title": "Onboarding", "instructions": "Keep answers short."},
                    desc="Instructions only apply while the thread currently has none.",
                ),
                req("Soft-delete thread", "DELETE", "/ai/sdk/threads/{{threadId}}"),
                req(
                    "Messages",
                    "GET",
                    "/ai/sdk/threads/{{threadId}}/messages",
                    query=[{"key": "after", "value": "0", "disabled": True}, {"key": "limit", "value": "50"}],
                ),
                req("Pending actions", "GET", "/ai/sdk/threads/{{threadId}}/actions"),
                req(
                    "Turn (creates thread if threadId omitted)",
                    "POST",
                    "/ai/sdk/turn",
                    json_body={
                        "threadId": "{{threadId}}",
                        "text": "Help me create my first budget.",
                        "surface": "ONBOARDING",
                        "instructions": "Guide a new user. Do not push withdrawals.",
                    },
                    tests=SAVE_THREAD + "\n" + SAVE_ACTION,
                ),
                req(
                    "Confirm prepared action",
                    "POST",
                    "/ai/sdk/actions/{{actionId}}/confirm",
                    json_body={"pin": "{{pin}}", "paramsHash": "{{paramsHash}}", "edits": {}},
                ),
                req("Cancel prepared action", "POST", "/ai/sdk/actions/{{actionId}}/cancel"),
            ],
        ),
        folder(
            "17 Analytics",
            "",
            [
                req(
                    "Ingest events",
                    "POST",
                    "/analytics/events",
                    json_body={
                        "events": [
                            {
                                "eventName": "screen_view",
                                "screenName": "dashboard",
                                "metadata": {"source": "postman"},
                                "devicePlatform": "android",
                                "appVersion": "1.0.0",
                                "sessionId": "{{deviceId}}",
                                "clientTimestamp": "2026-09-03T10:00:00Z",
                            }
                        ]
                    },
                ),
            ],
        ),
        folder(
            "18 Admin",
            "Requires ROLE_ADMIN (JWT with admin role). URL prefix /admin/** is also gated in SecurityConfig.",
            [
                req("Analytics summary", "GET", "/admin/analytics/summary", query=[{"key": "days", "value": "30"}]),
                req("Get system config", "GET", "/admin/config/psp.active"),
                req(
                    "Set system config",
                    "PUT",
                    "/admin/config/psp.active",
                    json_body={"value": "RUBIES", "description": "Active PSP"},
                ),
                req("Evict config cache", "POST", "/admin/config/cache/evict/psp.active"),
                req("Flush Monnie insight caches", "POST", "/admin/config/ai/flush-monnie-caches"),
                req("Payeelord sync catalog", "POST", "/admin/payeelord/sync-catalog"),
                req("Payeelord balance", "GET", "/admin/payeelord/balance"),
                req("Payeelord diagnose", "GET", "/admin/payeelord/diagnose"),
                req("Rubies revenue wallet", "GET", "/admin/rubies/revenue-wallet"),
                req(
                    "Rubies setup revenue wallet",
                    "POST",
                    "/admin/rubies/setup-revenue-wallet",
                    json_body={
                        "bvn": "{{bvn}}",
                        "firstName": "Moniewise",
                        "lastName": "Technologies",
                        "email": "ops@example.com",
                        "phone": "08012345678",
                        "dateOfBirth": "1990-01-01",
                        "displayName": "MONIEWISE TECHNOLOGIES",
                    },
                ),
                req(
                    "Rubies register existing revenue wallet",
                    "POST",
                    "/admin/rubies/register-revenue-wallet",
                    json_body={"accountNumber": "7012345678", "accountName": "MONIEWISE TECHNOLOGIES"},
                ),
                req("Delete Rubies revenue wallet config", "DELETE", "/admin/rubies/revenue-wallet"),
                req("Hot-reload Rubies API key", "POST", "/admin/rubies/update-api-key", json_body={"apiKey": "{{rubiesApiKey}}"}),
                req("Settle transfer by reference", "POST", "/admin/rubies/settle/{{providerRef}}"),
                req("Settle stuck transfers", "POST", "/admin/rubies/settle-stuck"),
                req(
                    "Backfill budget fees",
                    "POST",
                    "/admin/rubies/backfill-budget-fees",
                    query=[
                        {"key": "dryRun", "value": "true"},
                        {"key": "limit", "value": "100"},
                        {"key": "userId", "value": "", "disabled": True},
                        {"key": "budgetId", "value": "", "disabled": True},
                        {"key": "retryFailed", "value": "true"},
                    ],
                ),
                req(
                    "Reconciliation status",
                    "GET",
                    "/admin/reconciliation/status/{{userId}}",
                    query=[{"key": "lookbackDays", "value": "7", "disabled": True}],
                ),
                req("Reconciliation dry-run", "POST", "/admin/reconciliation/dry-run/{{userId}}"),
                req("Heal user", "POST", "/admin/reconciliation/heal-user/{{userId}}"),
                req(
                    "Run daily reconciliation",
                    "POST",
                    "/admin/reconciliation/run-daily",
                    query=[{"key": "providerName", "value": "RUBIES"}],
                ),
                req(
                    "Broadcast service outage",
                    "POST",
                    "/admin/notifications/service-outage",
                    json_body={
                        "title": "Service interruption",
                        "message": "Transfers delayed.",
                        "sendFcm": True,
                        "sendEmail": False,
                        "allUsers": True,
                        "includeTestAccounts": False,
                    },
                ),
                req(
                    "Broadcast maintenance",
                    "POST",
                    "/admin/notifications/maintenance",
                    json_body={"title": "Maintenance", "message": "Tonight 01:00 WAT", "allUsers": True, "sendFcm": True},
                ),
                req(
                    "Broadcast special",
                    "POST",
                    "/admin/notifications/special",
                    json_body={"title": "Promo", "message": "Try templates", "allUsers": True, "sendFcm": True},
                ),
                req("Create blog post", "POST", "/admin/blog", json_body={"title": "How to budget", "content": "Start with envelopes.", "excerpt": "Start here", "publish": True}),
                req("List all blog posts", "GET", "/admin/blog", query=[{"key": "page", "value": "0"}, {"key": "size", "value": "20"}]),
                req("Get blog post", "GET", "/admin/blog/{{blogPostId}}"),
                req("Update blog post", "PUT", "/admin/blog/{{blogPostId}}", json_body={"title": "How to budget", "content": "Updated.", "publish": True}),
                req(
                    "Upload blog media",
                    "POST",
                    "/admin/blog/{{blogPostId}}/media",
                    formdata=[
                        {"key": "file", "type": "file", "src": []},
                        {"key": "mediaType", "type": "text", "value": "IMAGE"},
                        {"key": "sortOrder", "type": "text", "value": "0"},
                    ],
                ),
                req(
                    "Add external blog media",
                    "POST",
                    "/admin/blog/{{blogPostId}}/media/external",
                    query=[
                        {"key": "mediaType", "value": "IMAGE"},
                        {"key": "url", "value": "https://example.com/cover.jpg"},
                        {"key": "sortOrder", "value": "0"},
                    ],
                ),
                req("Delete blog post", "DELETE", "/admin/blog/{{blogPostId}}"),
                req("Delete blog media", "DELETE", "/admin/blog/media/{{mediaId}}"),
            ],
        ),
        folder(
            "19 Webhooks (providers — no JWT)",
            "Open in SecurityConfig. Signatures are provider-specific. Bodies are placeholders — do not use as production replay.",
            [
                req("Monnify", "POST", "/api/webhooks/monnify", public=True, json_body={"eventType": "SUCCESSFUL_TRANSACTION", "eventData": {}}, extra_headers=[{"key": "monnify-signature", "value": ""}]),
                req("Payeelord", "POST", "/api/webhooks/payeelord", public=True, json_body={"event": "transaction.update"}),
                req("SecureWave", "POST", "/api/webhooks/securewave", public=True, json_body={}),
                req("Providus", "POST", "/api/webhooks/providus", public=True, json_body={}),
                req("Rubies", "POST", "/api/webhooks/rubies", public=True, json_body={"sessionId": "demo"}, extra_headers=[{"key": "X-Signature", "value": ""}]),
            ],
        ),
    ]

    collection = {
        "info": {
            "_postman_id": uid(),
            "name": "Moniewise Backend",
            "description": (
                "Session-authenticated REST for the Moniewise Spring Boot API.\n\n"
                "**Import both** this collection and `Moniewise-Local.postman_environment.json`.\n\n"
                "1. Set `baseUrl` (local `http://localhost:8080` or your Render host).\n"
                "2. Fill `email` / `password` (and `pin` for money moves).\n"
                "3. Run **Login** — tests write `accessToken` onto the collection and environment.\n"
                "4. Remaining folders inherit `Authorization: Bearer {{accessToken}}`.\n\n"
                "Public folders disable auth. `/admin/**` needs an admin JWT. "
                "Wrong-user AI thread/action lookups return **404**, not 403.\n\n"
                "Empty controller stubs (`GamificationController`, `InsightsController`, `GoalController`) "
                "have no mappings and are omitted."
            ),
            "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
        },
        "item": items,
        "auth": {
            "type": "bearer",
            "bearer": [{"key": "token", "value": "{{accessToken}}", "type": "string"}],
        },
        "variable": [
            {"key": "baseUrl", "value": "http://localhost:8080"},
            {"key": "accessToken", "value": ""},
            {"key": "email", "value": "you@example.com"},
            {"key": "password", "value": ""},
            {"key": "phone", "value": "08012345678"},
            {"key": "otp", "value": "123456"},
            {"key": "pin", "value": "1234"},
            {"key": "bvn", "value": "22222222222"},
            {"key": "deviceId", "value": "postman-device-1"},
            {"key": "nuban", "value": "0123456789"},
            {"key": "budgetId", "value": "1"},
            {"key": "envelopeId", "value": "1"},
            {"key": "threadId", "value": ""},
            {"key": "actionId", "value": ""},
            {"key": "paramsHash", "value": ""},
        ],
    }

    env = {
        "id": uid(),
        "name": "Moniewise Local",
        "values": [
            {"key": "baseUrl", "value": "http://localhost:8080", "enabled": True},
            {"key": "accessToken", "value": "", "enabled": True},
            {"key": "email", "value": "you@example.com", "enabled": True},
            {"key": "password", "value": "", "type": "secret", "enabled": True},
            {"key": "phone", "value": "08012345678", "enabled": True},
            {"key": "otp", "value": "", "enabled": True},
            {"key": "pin", "value": "", "type": "secret", "enabled": True},
            {"key": "bvn", "value": "", "type": "secret", "enabled": True},
            {"key": "deviceId", "value": "postman-device-1", "enabled": True},
            {"key": "nuban", "value": "0123456789", "enabled": True},
            {"key": "fcmToken", "value": "", "enabled": True},
            {"key": "googleIdToken", "value": "", "enabled": True},
            {"key": "budgetId", "value": "1", "enabled": True},
            {"key": "envelopeId", "value": "1", "enabled": True},
            {"key": "templateId", "value": "1", "enabled": True},
            {"key": "savingsId", "value": "1", "enabled": True},
            {"key": "transactionId", "value": "1", "enabled": True},
            {"key": "transactionRequestId", "value": "1", "enabled": True},
            {"key": "userId", "value": "1", "enabled": True},
            {"key": "notificationId", "value": "1", "enabled": True},
            {"key": "userBadgeId", "value": "1", "enabled": True},
            {"key": "disbursementId", "value": "1", "enabled": True},
            {"key": "threadId", "value": "", "enabled": True},
            {"key": "actionId", "value": "", "enabled": True},
            {"key": "paramsHash", "value": "", "enabled": True},
            {"key": "dataPlanId", "value": "", "enabled": True},
            {"key": "providerRef", "value": "", "enabled": True},
            {"key": "blogSlug", "value": "how-to-budget", "enabled": True},
            {"key": "blogPostId", "value": "1", "enabled": True},
            {"key": "mediaId", "value": "1", "enabled": True},
            {"key": "rubiesApiKey", "value": "", "type": "secret", "enabled": True},
        ],
        "_postman_variable_scope": "environment",
    }

    coll_path = ROOT / "Moniewise-Backend.postman_collection.json"
    env_path = ROOT / "Moniewise-Local.postman_environment.json"
    coll_path.write_text(json.dumps(collection, indent=2) + "\n", encoding="utf-8")
    env_path.write_text(json.dumps(env, indent=2) + "\n", encoding="utf-8")

    def count_reqs(nodes):
        n = 0
        for x in nodes:
            if "request" in x:
                n += 1
            n += count_reqs(x.get("item", []))
        return n

    print(f"Wrote {coll_path.name} ({count_reqs(items)} requests)")
    print(f"Wrote {env_path.name}")


if __name__ == "__main__":
    main()
