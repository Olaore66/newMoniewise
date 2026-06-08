package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.PayeelordDataPlan;
import com.moniewise.moniewise_backend.repository.PayeelordDataPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Periodically refreshes {@code payeelord_data_plans.cost_price} from Payeelord's
 * live price list — the data-side analogue of {@link ReconciliationScheduler}.
 *
 * <h3>⚠️ The actual scrape is NOT implemented yet — this is a scaffold</h3>
 * Payeelord does not document a catalog API; their integration notes say to
 * "open the dedicated tables page to copy" the price list by hand. That means
 * before {@link #scrapeCatalog()} can do real work, we need from the user/Payeelord:
 * <ul>
 *   <li>the exact page URL that lists current data plans (and whether it requires
 *       authentication — session cookie, API key in a query param, etc.)</li>
 *   <li>its structure — is it a rendered HTML table, a JSON endpoint behind the
 *       page, a downloadable CSV/PDF? This determines whether we even need an
 *       HTML parser (note: there is currently <b>no Jsoup or similar HTML-parsing
 *       library in {@code pom.xml}</b> — one would need to be added for table-scraping)</li>
 *   <li>a stable mapping from scraped rows back to Payeelord's {@code networkId}/
 *       {@code dataId} values (the identifiers {@code POST /buy/data} requires) —
 *       the price-list page may not expose these directly</li>
 * </ul>
 * Until then, {@link #scrapeCatalog()} throws {@link UnsupportedOperationException}
 * with this same explanation, the job logs a clear TODO and exits harmlessly, and
 * — critically — the feature defaults to <b>disabled</b>
 * ({@code system_config.payeelord.catalog.sync.enabled = false}, see the seed row
 * in {@code V13__create_payeelord_vas_tables.sql}) so nothing runs until both the
 * scrape target is known AND an admin deliberately flips the flag on.
 *
 * <h3>What IS fully built — the upsert pipeline around the seam</h3>
 * Once {@link #scrapeCatalog()} returns real {@link ScrapedPlan} rows, the rest of
 * this job is ready to go:
 * <ul>
 *   <li><b>Upserts {@code cost_price}</b> for existing plans (matched by {@code dataId})
 *       and inserts new ones — this column is scraper-owned, see
 *       {@link PayeelordDataPlan} column-ownership notes</li>
 *   <li><b>NEVER touches {@code markup_amount}</b> — that's the admin's margin and
 *       must survive every refresh untouched</li>
 *   <li><b>Deactivates</b> ({@code active = false}) plans that disappeared from the
 *       scrape — so the picker ({@code GET /vas/data/plans}) stops surfacing
 *       discontinued bundles without ever deleting the audit trail they're
 *       referenced from ({@code payeelord_vas_transactions.data_plan_id})</li>
 *   <li>Stamps {@code last_synced_at} on every row touched this run</li>
 * </ul>
 */
@Component
public class PayeelordCatalogSyncJob {

    private static final Logger logger = LoggerFactory.getLogger(PayeelordCatalogSyncJob.class);

    private final PayeelordDataPlanRepository dataPlanRepository;
    private final SystemConfigService systemConfig;

    public PayeelordCatalogSyncJob(PayeelordDataPlanRepository dataPlanRepository,
                                   SystemConfigService systemConfig) {
        this.dataPlanRepository = dataPlanRepository;
        this.systemConfig = systemConfig;
    }

    /**
     * Runs at 03:00 Africa/Lagos by default — an hour after {@link ReconciliationScheduler}'s
     * 02:00 run, to keep heavier scheduled jobs from overlapping. Configurable via
     * {@code payeelord.catalog.sync.cron} for ops flexibility, exactly like the
     * reconciliation scheduler's {@code moniewise.reconciliation.scheduler.cron}.
     */
    @Scheduled(cron = "${payeelord.catalog.sync.cron:0 0 3 * * ?}", zone = "Africa/Lagos")
    public void runCatalogSync() {
        if (!systemConfig.getBoolean(SystemConfigService.PAYEELORD_CATALOG_SYNC_ENABLED, false)) {
            logger.debug("[PayeelordCatalogSync] Disabled via system_config.{} — skipping run",
                    SystemConfigService.PAYEELORD_CATALOG_SYNC_ENABLED);
            return;
        }

        logger.info("[PayeelordCatalogSync] Starting catalog sync run");

        List<ScrapedPlan> scraped;
        try {
            scraped = scrapeCatalog();
        } catch (UnsupportedOperationException e) {
            logger.warn("[PayeelordCatalogSync] Skipped — scraper not yet implemented: {}", e.getMessage());
            return;
        } catch (Exception e) {
            logger.error("[PayeelordCatalogSync] Scrape failed — leaving existing catalog untouched", e);
            return;
        }

        if (scraped == null || scraped.isEmpty()) {
            logger.warn("[PayeelordCatalogSync] Scrape returned no plans — refusing to deactivate the " +
                    "entire catalog on what's more likely a scrape failure than a real empty price list. " +
                    "Leaving existing rows untouched.");
            return;
        }

        upsertCatalog(scraped);
    }

    /**
     * Applies a freshly-scraped price list to the local catalog: upserts
     * {@code cost_price} (by {@code dataId}), inserts unseen plans, deactivates
     * ones no longer present, and stamps {@code last_synced_at} throughout.
     *
     * <p>Package-private + transactional so it can be unit-tested directly with a
     * hand-built {@code List<ScrapedPlan>} once the scraper exists, without needing
     * to mock the HTTP/HTML layer.
     */
    @Transactional
    void upsertCatalog(List<ScrapedPlan> scraped) {
        LocalDateTime now = LocalDateTime.now();
        Set<String> seenDataIds = new HashSet<>();
        int created = 0;
        int updated = 0;

        for (ScrapedPlan sp : scraped) {
            if (sp.dataId() == null || sp.dataId().isBlank() || sp.costPrice() == null) {
                logger.warn("[PayeelordCatalogSync] Skipping malformed scrape row: {}", sp);
                continue;
            }
            seenDataIds.add(sp.dataId());

            PayeelordDataPlan plan = dataPlanRepository.findByDataId(sp.dataId()).orElseGet(PayeelordDataPlan::new);
            boolean isNew = plan.getId() == null;

            plan.setDataId(sp.dataId());
            plan.setNetworkId(sp.networkId());
            plan.setNetworkName(sp.networkName());
            plan.setPlanType(sp.planType());
            plan.setPlanName(sp.planName());
            plan.setSizeLabel(sp.sizeLabel());
            plan.setValidityLabel(sp.validityLabel());

            // ── cost_price: scraper-owned, always overwritten ────────────────
            plan.setCostPrice(sp.costPrice());
            // ── markup_amount: ADMIN-OWNED — never touched here. New rows keep
            //    the entity default of BigDecimal.ZERO until an admin sets one. ──

            plan.setActive(true);
            plan.setLastSyncedAt(now);
            plan.setUpdatedAt(now);
            if (isNew) {
                plan.setCreatedAt(now);
            }

            dataPlanRepository.save(plan);
            if (isNew) created++; else updated++;
        }

        int deactivated = 0;
        for (PayeelordDataPlan existing : dataPlanRepository.findAll()) {
            if (existing.isActive() && !seenDataIds.contains(existing.getDataId())) {
                existing.setActive(false);
                existing.setLastSyncedAt(now);
                existing.setUpdatedAt(now);
                dataPlanRepository.save(existing);
                deactivated++;
            }
        }

        logger.info("[PayeelordCatalogSync] Sync complete — created={} updated={} deactivated={} (scraped {} rows)",
                created, updated, deactivated, scraped.size());
    }

    /**
     * THE SEAM — replace this with a real implementation once the scrape target is known.
     *
     * <p>See the class Javadoc for exactly what's missing (URL, structure, auth,
     * and a {@code networkId}/{@code dataId} mapping). Until then this throws so the
     * caller can log-and-skip cleanly rather than silently doing nothing forever.
     *
     * <p>Likely shape of a real implementation, once unblocked:
     * <pre>
     *   1. Fetch the price-list page/endpoint (Jsoup for HTML, RestTemplate for JSON/CSV —
     *      whichever the confirmed structure calls for; a parsing dependency may need
     *      adding to pom.xml)
     *   2. Parse each row into a {@link ScrapedPlan}, resolving networkId/dataId via
     *      whatever stable mapping the user/Payeelord confirms
     *   3. Return the full list — {@link #upsertCatalog} handles the rest
     * </pre>
     */
    List<ScrapedPlan> scrapeCatalog() {
        throw new UnsupportedOperationException(
                "Payeelord catalog scraper is not yet implemented — the scrape target " +
                "(page URL, structure, auth, and a networkId/dataId mapping) is still " +
                "unknown. See PayeelordCatalogSyncJob class Javadoc for exactly what's " +
                "needed before this seam can be filled in. The job is also gated off by " +
                "system_config.payeelord.catalog.sync.enabled=false, so this exception " +
                "only surfaces if someone deliberately flips that flag on early.");
    }

    /**
     * One row of freshly-scraped catalog data — exactly the fields
     * {@link #upsertCatalog} needs to write into {@link PayeelordDataPlan},
     * deliberately excluding {@code markupAmount} (admin-owned, never scraped).
     */
    public record ScrapedPlan(
            String networkId,
            String networkName,
            String dataId,
            String planType,
            String planName,
            String sizeLabel,
            String validityLabel,
            BigDecimal costPrice
    ) {}
}
