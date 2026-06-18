package com.moniewise.moniewise_backend.entity;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A single data-bundle product from Payeelord's price-list catalog
 * (e.g., "MTN — 1GB SME — 30 days — ₦509").
 *
 * <h3>Column ownership — read this before touching either price column</h3>
 * <ul>
 *   <li>{@link #costPrice} is OWNED BY THE SCRAPER ({@code PayeelordCatalogSyncJob}).
 *       It gets overwritten on every sync run to track Payeelord's live price.</li>
 *   <li>{@link #markupAmount} is OWNED BY ADMINS. The scraper must NEVER write to
 *       this column — that's how your margin survives every catalog refresh.</li>
 * </ul>
 *
 * <p>The price the user pays is {@code costPrice + markupAmount} — see
 * {@code PayeelordPricingService#priceDataPlan}.
 */
@Entity
@Table(name = "payeelord_data_plans", indexes = {
        @Index(name = "idx_payeelord_plans_network", columnList = "network_id"),
        @Index(name = "idx_payeelord_plans_active", columnList = "is_active")
})
public class PayeelordDataPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Payeelord's networkId — e.g. "1" = MTN, "2" = GLO, "3" = 9MOBILE, "4" = AIRTEL. */
    @Column(name = "network_id", nullable = false, length = 10)
    private String networkId;

    /** Payeelord's dataId — the unique plan identifier required by POST /buy/data. */
    @Column(name = "data_id", nullable = false, unique = true, length = 20)
    private String dataId;

    /** MTN | GLO | AIRTEL | 9MOBILE — human-readable network name for display. */
    @Column(name = "network_name", nullable = false, length = 20)
    private String networkName;

    /** e.g. "SME", "GIFTING", "DATASHARE", "COOPERATE GIFTING", "AIRTEL DIRECT". */
    @Column(name = "plan_type", length = 40)
    private String planType;

    /** e.g. "1GB SME 30 days" — shown to the user in the plan picker. */
    @Column(name = "plan_name", nullable = false, length = 120)
    private String planName;

    /** e.g. "1GB", "500MB". */
    @Column(name = "size_label", length = 20)
    private String sizeLabel;

    /** e.g. "30 days", "7 days". */
    @Column(name = "validity_label", length = 20)
    private String validityLabel;

    /** Payeelord's price for this plan — OVERWRITTEN BY THE SCRAPER on every sync. */
    @Column(name = "cost_price", nullable = false)
    private BigDecimal costPrice;

    /** Your margin on top — ADMIN-OWNED, the scraper never writes here. Defaults to zero. */
    @Column(name = "markup_amount", nullable = false)
    private BigDecimal markupAmount = BigDecimal.ZERO;

    /** False once the scraper notices this plan has disappeared from Payeelord's list. */
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    // ── Convenience ───────────────────────────────────────────────────────────

    /** What the user pays = Payeelord's price rounded up to the next multiple of ₦100. */
    @Transient
    public BigDecimal getSellingPrice() {
        if (costPrice == null || costPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        java.math.BigDecimal hundred = new java.math.BigDecimal("100");
        return costPrice
                .divide(hundred, 0, java.math.RoundingMode.CEILING)
                .multiply(hundred);
    }

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public Long getId()                              { return id; }
    public void setId(Long id)                       { this.id = id; }

    public String getNetworkId()                     { return networkId; }
    public void setNetworkId(String networkId)       { this.networkId = networkId; }

    public String getDataId()                        { return dataId; }
    public void setDataId(String dataId)             { this.dataId = dataId; }

    public String getNetworkName()                   { return networkName; }
    public void setNetworkName(String networkName)   { this.networkName = networkName; }

    public String getPlanType()                      { return planType; }
    public void setPlanType(String planType)         { this.planType = planType; }

    public String getPlanName()                      { return planName; }
    public void setPlanName(String planName)         { this.planName = planName; }

    public String getSizeLabel()                     { return sizeLabel; }
    public void setSizeLabel(String sizeLabel)       { this.sizeLabel = sizeLabel; }

    public String getValidityLabel()                 { return validityLabel; }
    public void setValidityLabel(String validityLabel) { this.validityLabel = validityLabel; }

    public BigDecimal getCostPrice()                 { return costPrice; }
    public void setCostPrice(BigDecimal costPrice)   { this.costPrice = costPrice; }

    public BigDecimal getMarkupAmount()              { return markupAmount; }
    public void setMarkupAmount(BigDecimal markupAmount) { this.markupAmount = markupAmount; }

    public boolean isActive()                        { return active; }
    public void setActive(boolean active)            { this.active = active; }

    public LocalDateTime getLastSyncedAt()           { return lastSyncedAt; }
    public void setLastSyncedAt(LocalDateTime lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }

    public LocalDateTime getCreatedAt()              { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt()              { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
