package com.expense_management_service.entity;

import com.expense_management_service.enums.TaxSource;
import com.expense_management_service.enums.TaxTreatment;
import com.expense_management_service.enums.TaxType;
import com.expense_management_service.enums.TaxValidationStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "expense_line_item")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class ExpenseLineItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "line_item_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID lineItemId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id", nullable = false)
    @ToString.Exclude
    private ExpenseReport report;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    @ToString.Exclude
    private ExpenseCategory category;

    @Column(name = "expense_date", nullable = false)
    private LocalDate expenseDate;

    @Column(name = "merchant_name", length = 255)
    private String merchantName;

    @Lob
    @Column(name = "description")
    private String description;

    @Column(name = "amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal amount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "currency_id", nullable = false)
    @ToString.Exclude
    private Currency currency;

    @Column(name = "exchange_rate", precision = 19, scale = 6)
    private BigDecimal exchangeRate;

    @Column(name = "base_amount", precision = 19, scale = 4)
    private BigDecimal baseAmount;

    @Column(name = "tax_amount", precision = 19, scale = 4)
    private BigDecimal taxAmount;

    /** Derived: {@code amount - taxAmount}, recalculated server-side on every save — never accepted from the client. */
    @Column(name = "net_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal netAmount;

    // ---- Tax snapshot: what applied to this line, independent of later changes to the tax master.
    // Recalculated on each save while the report is editable, frozen at submission.

    /** Soft reference (no FK) - the copied values below are what count. */
    @Column(name = "tax_code_id")
    private UUID taxCodeId;

    @Column(name = "tax_code", length = 50)
    private String taxCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "tax_type", length = 32)
    private TaxType taxType;

    @Column(name = "tax_rate_percent", precision = 5, scale = 2)
    private BigDecimal taxRatePercent;

    @Enumerated(EnumType.STRING)
    @Column(name = "tax_treatment", length = 16)
    private TaxTreatment taxTreatment;

    /** What the configuration says the tax is; kept beside {@code taxAmount} so overrides stay visible. */
    @Column(name = "calculated_tax_amount", precision = 19, scale = 4)
    private BigDecimal calculatedTaxAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "tax_source", length = 32)
    private TaxSource taxSource;

    @Column(name = "tax_override_reason", length = 500)
    private String taxOverrideReason;

    /** Copied from the tax code when the snapshot is taken. */
    @Column(name = "itc_recoverable_percent", precision = 5, scale = 2)
    private BigDecimal itcRecoverablePercent;

    /** round(taxAmount x itcRecoverablePercent / 100), transaction currency. */
    @Column(name = "recoverable_tax_amount", precision = 19, scale = 4)
    private BigDecimal recoverableTaxAmount;

    /** round(taxAmount x exchangeRate) in base-currency minor units. */
    @Column(name = "base_tax_amount", precision = 19, scale = 4)
    private BigDecimal baseTaxAmount;

    /** baseAmount - baseTaxAmount, so the two always add up to baseAmount. */
    @Column(name = "base_net_amount", precision = 19, scale = 4)
    private BigDecimal baseNetAmount;

    @Column(name = "base_recoverable_tax_amount", precision = 19, scale = 4)
    private BigDecimal baseRecoverableTaxAmount;

    /** OCR's tax, copied when the receipt is confirmed so comparisons survive OCR re-runs. */
    @Column(name = "ocr_tax_amount", precision = 19, scale = 4)
    private BigDecimal ocrTaxAmount;

    /** OCR's confidence (0-1) in {@code ocrTaxAmount}; below TAX_OCR_MIN_CONFIDENCE it is ignored for comparison. */
    @Column(name = "ocr_tax_confidence", precision = 5, scale = 4)
    private BigDecimal ocrTaxConfidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "tax_validation_status", length = 32)
    private TaxValidationStatus taxValidationStatus;

    /** Comma-separated reason codes behind {@code taxValidationStatus}, e.g. "OCR_MISMATCH,OVERRIDE". */
    @Column(name = "tax_validation_reasons", length = 255)
    private String taxValidationReasons;

    /**
     * Last time the tax changed while the report was being corrected after a submission. A Finance
     * verification older than this is reopened on resume (tax-only changes reset Finance, not the manager).
     */
    @Column(name = "tax_revised_at")
    private LocalDateTime taxRevisedAt;

    /** When the snapshot was frozen (submission); null while still editable. */
    @Column(name = "tax_snapshot_at")
    private LocalDateTime taxSnapshotAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cost_center_id")
    @ToString.Exclude
    private CostCenter costCenter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    @ToString.Exclude
    private ProjectCache project;

    @Column(name = "client_billable")
    private Boolean clientBillable;

    /**
     * Frozen snapshot of the RMS client resolved for {@link #project} at the moment this line
     * item was last saved with {@code clientBillable = true} — deliberately independent of
     * whatever {@code ProjectCache}/RMS say afterward, so display and audit trail stay accurate
     * even if the client record later changes or the RMS lookup becomes unavailable. Null for
     * any non-billable line item. See {@code ExpenseLineItemServiceImpl.resolveClientBillableProject}.
     */
    @Column(name = "resolved_client_id")
    private UUID resolvedClientId;

    @Column(name = "resolved_client_name", length = 255)
    private String resolvedClientName;

    @Column(name = "line_status", length = 255)
    private String lineStatus;

    /** "MANUAL" (created directly by the employee) or "OCR" (created via receipt confirmation). Purely informational — nothing branches on it today. */
    @Column(name = "created_by", length = 20, nullable = false)
    @Builder.Default
    private String createdBy = "MANUAL";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "lineItem", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @ToString.Exclude
    private List<Receipt> receipts = new ArrayList<>();

    /** Snapshotted tax components; their amounts sum to {@code taxAmount}. Empty for legacy / untaxed lines. */
    @OneToMany(mappedBy = "lineItem", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sequence ASC")
    @Builder.Default
    @ToString.Exclude
    private List<ExpenseLineTaxComponent> taxComponents = new ArrayList<>();

    @OneToMany(mappedBy = "lineItem", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @ToString.Exclude
    private List<CostAllocation> costAllocations = new ArrayList<>();

    /** Empty for a NORMAL line item (uses {@code costCenter} above / {@code report.costCenter} directly); two or more rows for a SPLIT line item. See {@code ExpenseSplit}'s own javadoc. */
    @OneToMany(mappedBy = "lineItem", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @ToString.Exclude
    private List<ExpenseSplit> expenseSplits = new ArrayList<>();

    @OneToMany(mappedBy = "lineItem", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @ToString.Exclude
    private List<VerificationQuery> verificationQueries = new ArrayList<>();

    @OneToMany(mappedBy = "lineItem", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @ToString.Exclude
    private List<InvoiceSync> invoiceSyncs = new ArrayList<>();

    @OneToMany(mappedBy = "lineItem", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @ToString.Exclude
    private List<PolicyViolation> policyViolations = new ArrayList<>();
}
