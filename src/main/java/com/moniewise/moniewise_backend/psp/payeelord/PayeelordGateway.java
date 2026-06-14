package com.moniewise.moniewise_backend.psp.payeelord;

import com.moniewise.moniewise_backend.psp.payeelord.dto.PayeelordAirtimePurchaseRequest;
import com.moniewise.moniewise_backend.psp.payeelord.dto.PayeelordAirtimePurchaseResponse;
import com.moniewise.moniewise_backend.psp.payeelord.dto.PayeelordDataPurchaseRequest;
import com.moniewise.moniewise_backend.psp.payeelord.dto.PayeelordDataPurchaseResponse;
import com.moniewise.moniewise_backend.service.SystemConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

/**
 * Thin HTTP client for Payeelord's Airtime/Data VAS API.
 *
 * <p>Payeelord is a VAS/biller reseller, NOT a transfer PSP — it intentionally does
 * NOT implement {@code PaymentGateway}. {@link #purchaseAirtime} and
 * {@link #purchaseData} return Payeelord's own response DTOs (not booleans/strings),
 * because the caller ({@code PayeelordVasService}) needs the full synchronous
 * response — {@code transaction_id}, {@code balance_before/after}, the exact
 * {@code status} — to record an accurate audit trail and decide whether to reverse
 * the user's wallet debit.
 *
 * <h3>This integration is SYNCHRONOUS</h3>
 * Per Payeelord's own integration notes: <em>"A transaction webhook fires only
 * after the purchase endpoint returns its JSON response."</em> The HTTP response
 * from {@code /buy/airtime} / {@code /buy/data} is the source of truth; the webhook
 * (see {@link #validateWebhookSignature}) is a secondary audit confirmation only.
 *
 * <h3>Configuration</h3>
 * <ul>
 *   <li>{@code PAYEELORD_API_KEY} env var (→ {@code payeelord.api.key}) — sent as
 *       {@code Authorization: Bearer {key}}</li>
 *   <li>{@code PAYEELORD_BASE_URL} env var (→ {@code payeelord.base.url}, default
 *       {@code https://payeelord.com/api}) — also runtime-overridable via
 *       {@code system_config.payeelord.api.base_url}, the latter wins if set</li>
 * </ul>
 */
@Component
public class PayeelordGateway {

    public static final String PROVIDER_NAME = "PAYEELORD";

    private static final Logger logger = LoggerFactory.getLogger(PayeelordGateway.class);

    private final RestTemplate restTemplate;
    private final SystemConfigService systemConfig;

    @Value("${payeelord.api.key:}")
    private String apiKey;

    @Value("${payeelord.base.url:https://api.payeelord.com/api}")
    private String defaultBaseUrl;

    /**
     * Optional explicit webhook secret override. Per Payeelord's docs, webhook
     * signatures normally use the CURRENT API KEY itself as the HMAC secret
     * ("regenerating the API key also rotates webhook signing automatically"),
     * so {@link #validateWebhookSignature} falls back to {@link #apiKey} when
     * this is blank. Only set {@code PAYEELORD_WEBHOOK_SECRET} if Payeelord ever
     * issues you a dedicated signing secret distinct from the API key.
     */
    @Value("${payeelord.webhook.secret:}")
    private String webhookSecretOverride;

    public PayeelordGateway(RestTemplate restTemplate, SystemConfigService systemConfig) {
        this.restTemplate = restTemplate;
        this.systemConfig = systemConfig;
    }

    // ── Startup validation ────────────────────────────────────────────────────

    @PostConstruct
    public void validateConfiguration() {
        if (apiKey == null || apiKey.isBlank()) {
            logger.error("[Payeelord] PAYEELORD_API_KEY (payeelord.api.key) is not set — " +
                    "all Payeelord purchase calls will fail with 401. Set the env var before going live.");
        }
        logger.info("[Payeelord] Gateway initialised. base_url={}", baseUrl());
    }

    // ── Identity ──────────────────────────────────────────────────────────────

    public String getProviderName() {
        return PROVIDER_NAME;
    }

    // ── Airtime purchase ──────────────────────────────────────────────────────

    /**
     * Calls {@code POST /buy/airtime}. SYNCHRONOUS — the returned response's
     * {@code status} ("successful" | "failed" | "processing") is authoritative.
     *
     * <p>Never throws on a well-formed "failed" response — the caller needs that
     * response body to reverse the wallet debit and record the failure reason.
     * Only throws on transport-level failures (timeouts, malformed/empty bodies,
     * non-2xx HTTP statuses) where we genuinely don't know what happened — those
     * are the dangerous "ambiguous" cases the caller must treat specially
     * (the float may have been debited even though we got no usable response).
     *
     * @param network      MTN | GLO | AIRTEL | 9MOBILE
     * @param mobileNumber recipient line, 11–20 digits
     * @param amount       Naira amount, 10–5000 inclusive
     */
    public PayeelordAirtimePurchaseResponse purchaseAirtime(String network, String mobileNumber, BigDecimal amount) {
        String url = baseUrl() + "/buy/airtime";
        PayeelordAirtimePurchaseRequest req = new PayeelordAirtimePurchaseRequest(network, mobileNumber, amount);

        logger.info("[Payeelord] Buying airtime: network={} mobile={} amount={}", network, mobileNumber, amount);

        try {
            ResponseEntity<PayeelordAirtimePurchaseResponse> response =
                    restTemplate.exchange(url, HttpMethod.POST,
                            new HttpEntity<>(req, authHeaders()),
                            PayeelordAirtimePurchaseResponse.class);

            PayeelordAirtimePurchaseResponse body = response.getBody();
            if (body == null || body.getStatus() == null) {
                throw new PayeelordAmbiguousResponseException(
                        "Payeelord airtime purchase returned an empty/unreadable body — " +
                                "outcome is AMBIGUOUS, the float may have been debited. " +
                                "Reconcile via GET /data-transactions before reversing the user's wallet.");
            }

            logger.info("[Payeelord] Airtime purchase result: mobile={} status={} txnId={}",
                    mobileNumber, body.getStatus(), body.getTransactionId());
            return body;

        } catch (PayeelordAmbiguousResponseException e) {
            throw e;
        } catch (HttpStatusCodeException e) {
            // Payeelord may put a structured failure body even on non-2xx — try to read it as a failure,
            // but if we can't parse it, treat it as ambiguous (don't blindly assume failure).
            PayeelordAirtimePurchaseResponse parsed = tryParseFailureBody(e.getResponseBodyAsString(),
                    PayeelordAirtimePurchaseResponse.class, e);
            if (parsed != null) return parsed;
            logger.error("[Payeelord] Airtime purchase HTTP error (ambiguous outcome): mobile={} status={} body={}",
                    mobileNumber, e.getStatusCode(), e.getResponseBodyAsString());
            throw new PayeelordAmbiguousResponseException(
                    "Payeelord airtime purchase returned HTTP " + e.getStatusCode() +
                            " with an unparseable body — outcome is AMBIGUOUS.", e);
        } catch (ResourceAccessException e) {
            // Timeout / connection failure — we genuinely don't know if Payeelord processed the debit.
            logger.error("[Payeelord] Airtime purchase network error (ambiguous outcome): mobile={} error={}",
                    mobileNumber, e.getMessage());
            throw new PayeelordAmbiguousResponseException(
                    "Payeelord airtime purchase timed out / connection failed — outcome is AMBIGUOUS. " +
                            "Do NOT assume failure; reconcile via GET /data-transactions before reversing.", e);
        }
    }

    // ── Data purchase ─────────────────────────────────────────────────────────

    /**
     * Calls {@code POST /buy/data}. SYNCHRONOUS — same authoritative-response and
     * ambiguous-outcome semantics as {@link #purchaseAirtime}.
     *
     * @param networkId Payeelord's numeric network id from the catalog (1=MTN, 2=GLO, 3=9MOBILE, 4=AIRTEL)
     * @param dataId    Payeelord's plan id from the catalog
     * @param mobileNumber recipient line
     */
    public PayeelordDataPurchaseResponse purchaseData(String networkId, String dataId, String dataType, String mobileNumber) {
        String url = baseUrl() + "/data";
        PayeelordDataPurchaseRequest req = new PayeelordDataPurchaseRequest(networkId, dataId, dataType, mobileNumber);

        logger.info("[Payeelord] Buying data: networkId={} dataId={} dataType={} mobile={}",
                networkId, dataId, dataType, mobileNumber);

        try {
            ResponseEntity<PayeelordDataPurchaseResponse> response =
                    restTemplate.exchange(url, HttpMethod.POST,
                            new HttpEntity<>(req, authHeaders()),
                            PayeelordDataPurchaseResponse.class);

            PayeelordDataPurchaseResponse body = response.getBody();
            if (body == null || body.getStatus() == null) {
                throw new PayeelordAmbiguousResponseException(
                        "Payeelord data purchase returned an empty/unreadable body — " +
                                "outcome is AMBIGUOUS, the float may have been debited. " +
                                "Reconcile via GET /data-transactions before reversing the user's wallet.");
            }

            logger.info("[Payeelord] Data purchase result: mobile={} dataId={} status={} txnId={}",
                    mobileNumber, dataId, body.getStatus(), body.getTransactionId());
            return body;

        } catch (PayeelordAmbiguousResponseException e) {
            throw e;
        } catch (HttpStatusCodeException e) {
            PayeelordDataPurchaseResponse parsed = tryParseFailureBody(e.getResponseBodyAsString(),
                    PayeelordDataPurchaseResponse.class, e);
            if (parsed != null) return parsed;
            logger.error("[Payeelord] Data purchase HTTP error (ambiguous outcome): mobile={} status={} body={}",
                    mobileNumber, e.getStatusCode(), e.getResponseBodyAsString());
            throw new PayeelordAmbiguousResponseException(
                    "Payeelord data purchase returned HTTP " + e.getStatusCode() +
                            " with an unparseable body — outcome is AMBIGUOUS.", e);
        } catch (ResourceAccessException e) {
            logger.error("[Payeelord] Data purchase network error (ambiguous outcome): mobile={} error={}",
                    mobileNumber, e.getMessage());
            throw new PayeelordAmbiguousResponseException(
                    "Payeelord data purchase timed out / connection failed — outcome is AMBIGUOUS. " +
                            "Do NOT assume failure; reconcile via GET /data-transactions before reversing.", e);
        }
    }

    // ── Webhook helpers (audit-trail confirmation only — see class Javadoc) ──

    /**
     * Best-effort signature check for Payeelord's webhook callbacks.
     *
     * <p><strong>STATUS: header name and exact signing scheme are UNDOCUMENTED.</strong>
     * Payeelord's docs state only that <em>"Webhook signatures use your current API
     * key as the HMAC secret, so regenerating the API key also rotates webhook
     * signing automatically"</em> — confirming HMAC + API-key-as-secret, but not
     * which header carries the digest or which hash algorithm is used. We mirror
     * Rubies' HMAC-SHA512 hex-digest convention as the most likely candidate.
     *
     * <p>This method intentionally ACCEPTS (returns {@code true}) when no signature
     * was supplied or the secret is unavailable, logging a security warning instead
     * of rejecting — so legitimate webhooks are never silently dropped while this
     * scheme is unverified. Once you've captured a real webhook delivery and
     * confirmed the actual header name + algorithm, tighten this to reject on
     * mismatch (mirror {@code RubiesGateway#validateWebhookSignature}).
     *
     * @param signature  raw signature header value, or {@code null} if the caller found none
     * @param rawPayload the exact raw request body bytes as a string (must match what was signed)
     */
    public boolean validateWebhookSignature(String signature, String rawPayload) {
        String secret = (webhookSecretOverride != null && !webhookSecretOverride.isBlank())
                ? webhookSecretOverride
                : apiKey;

        if (secret == null || secret.isBlank()) {
            logger.warn("[Payeelord][SECURITY] No webhook secret/API key configured — " +
                    "accepting webhook without verification.");
            return true;
        }
        if (signature == null || signature.isBlank()) {
            logger.warn("[Payeelord][WEBHOOK] No signature header found on incoming webhook — " +
                    "the carrier header name is still unconfirmed (see PayeelordGateway Javadoc). " +
                    "Accepting as audit-trail-only pending live verification.");
            return true;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] hashBytes = mac.doFinal(rawPayload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hashBytes) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) hex.append('0');
                hex.append(h);
            }
            String expected = hex.toString();
            boolean valid = expected.equalsIgnoreCase(signature.trim());
            if (!valid) {
                logger.warn("[Payeelord][WEBHOOK] Signature mismatch (scheme unverified — see Javadoc). " +
                        "expected(sha512 hex)={} received={}", expected, signature.trim());
            }
            return valid;
        } catch (Exception e) {
            logger.error("[Payeelord][WEBHOOK] HMAC validation threw an exception", e);
            return true; // fail-open while the scheme is unverified — see Javadoc
        }
    }

    // ── Wallet balance (admin float monitor) ──────────────────────────────────

    /**
     * Calls {@code GET /check/balance} to read our Payeelord float balance.
     *
     * <p>Response shape: {@code {"status":"successful","wallet_balance":"N34874.00"}}.
     * Returns {@link java.util.Optional#empty()} on any transport/parse failure —
     * the only caller is a best-effort low-balance monitor, so a transient outage
     * must never page anyone or crash a scheduled run.
     */
    public java.util.Optional<BigDecimal> checkWalletBalance() {
        String url = baseUrl() + "/check/balance";
        try {
            ResponseEntity<java.util.Map> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(authHeaders()), java.util.Map.class);
            java.util.Map<?, ?> body = response.getBody();
            if (body == null) return java.util.Optional.empty();
            Object raw = body.get("wallet_balance");
            if (raw == null) return java.util.Optional.empty();
            // Strip currency noise: "N34874.00" / "₦ 34,874.00" → 34874.00
            String cleaned = raw.toString().replaceAll("[^0-9.]", "");
            if (cleaned.isBlank()) return java.util.Optional.empty();
            return java.util.Optional.of(new BigDecimal(cleaned));
        } catch (Exception e) {
            logger.warn("[Payeelord] check/balance failed: {}", e.getMessage());
            return java.util.Optional.empty();
        }
    }

    // ── Catalog reads (used by PayeelordCatalogSyncJob) ───────────────────────

    /** {@code GET /datatypes} → list of {@code {type, network_id}}. Empty on failure. */
    public java.util.List<java.util.Map<String, Object>> getDataTypes() {
        return getListData(baseUrl() + "/datatypes", null);
    }

    /** {@code GET /all-network} → list of {@code {network_id, network_name}}. Empty on failure. */
    public java.util.List<java.util.Map<String, Object>> getNetworks() {
        return getListData(baseUrl() + "/all-network", null);
    }

    /**
     * {@code GET /data-plan} (with a JSON body, per Payeelord's contract) →
     * list of {@code {id, dataId, dataName, description, amount, networkId}}.
     */
    public java.util.List<java.util.Map<String, Object>> getDataPlans(String dataType, String networkId) {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("dataType", dataType);
        // Payeelord's sample sends networkId as a JSON number.
        try {
            body.put("networkId", Integer.parseInt(networkId));
        } catch (NumberFormatException e) {
            body.put("networkId", networkId);
        }
        return getListData(baseUrl() + "/data-plan", body);
    }

    /**
     * Shared GET helper that unwraps Payeelord's {@code {"data": [...]}} envelope.
     * Sends an optional JSON body (Payeelord's {@code /data-plan} is a GET-with-body).
     */
    @SuppressWarnings("unchecked")
    private java.util.List<java.util.Map<String, Object>> getListData(String url, Object body) {
        try {
            HttpEntity<?> entity = (body != null)
                    ? new HttpEntity<>(body, authHeaders())
                    : new HttpEntity<>(authHeaders());
            ResponseEntity<java.util.Map> resp =
                    restTemplate.exchange(url, HttpMethod.GET, entity, java.util.Map.class);
            java.util.Map<?, ?> m = resp.getBody();
            if (m == null) return java.util.List.of();
            Object data = m.get("data");
            if (!(data instanceof java.util.List<?> list)) return java.util.List.of();
            java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
            for (Object o : list) {
                if (o instanceof java.util.Map<?, ?> row) {
                    out.add((java.util.Map<String, Object>) row);
                }
            }
            return out;
        } catch (Exception e) {
            logger.warn("[Payeelord] GET {} failed: {}", url, e.getMessage());
            return java.util.List.of();
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Resolves the active base URL: {@code system_config.payeelord.api.base_url}
     * wins when set (so it can be changed at runtime without a redeploy), falling
     * back to the {@code payeelord.base.url} property / {@code PAYEELORD_BASE_URL} env var.
     */
    private String baseUrl() {
        return systemConfig.getString(SystemConfigService.PAYEELORD_API_BASE_URL, defaultBaseUrl);
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        headers.set("Authorization", "Bearer " + apiKey);
        return headers;
    }

    /**
     * Payeelord may return failure bodies with a non-2xx HTTP status (their docs
     * show {@code {"status": "failed", "message": "..."}} shapes). If the error
     * body parses cleanly into a response DTO with a "failed" status, treat it as
     * a definitive failure (safe to reverse); otherwise return {@code null} so the
     * caller treats it as ambiguous instead.
     */
    private <T> T tryParseFailureBody(String rawBody, Class<T> type, Exception cause) {
        if (rawBody == null || rawBody.isBlank()) return null;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            T parsed = mapper.readValue(rawBody, type);
            if (parsed instanceof PayeelordAirtimePurchaseResponse r && r.getStatus() != null) {
                return type.cast(r);
            }
            if (parsed instanceof PayeelordDataPurchaseResponse r && r.getStatus() != null) {
                return type.cast(r);
            }
            return null;
        } catch (Exception parseEx) {
            logger.debug("[Payeelord] Could not parse error body as a structured response: {}", parseEx.getMessage());
            return null;
        }
    }

    /**
     * Thrown when we genuinely cannot determine whether a Payeelord purchase
     * succeeded or failed (timeouts, malformed bodies, unparseable error responses).
     *
     * <p>This is the critical "danger zone" of a synchronous reseller integration:
     * the float may have already been debited even though we have no confirmation.
     * {@code PayeelordVasService} MUST catch this distinctly from a clean "failed"
     * response — it should leave the transaction {@code PENDING} (NOT auto-reverse
     * the user's wallet) and flag it for reconciliation via
     * {@code GET /data-transactions}, exactly as Payeelord's own docs recommend.
     */
    public static class PayeelordAmbiguousResponseException extends RuntimeException {
        public PayeelordAmbiguousResponseException(String message) {
            super(message);
        }
        public PayeelordAmbiguousResponseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
