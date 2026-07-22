package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "expense_report", uniqueConstraints = @UniqueConstraint(columnNames = "report_number"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class ExpenseReport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "report_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID reportId;

    @Column(name = "report_number", length = 255, nullable = false)
    private String reportNumber;

    @Column(name = "employee_id", length = 255, nullable = false)
    private String employeeId;

    @Column(name = "title", length = 255, nullable = false)
    private String title;

    @Lob
    @Column(name = "business_purpose")
    private String businessPurpose;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cost_center_id", nullable = false)
    @ToString.Exclude
    private CostCenter costCenter;

    @Column(name = "report_status", length = 255)
    private String reportStatus;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "currency_id", nullable = false)
    @ToString.Exclude
    private Currency currency;

    @Column(name = "total_amount", precision = 19, scale = 4)
    private BigDecimal totalAmount;

    @Column(name = "reimbursable_amount", precision = 19, scale = 4)
    private BigDecimal reimbursableAmount;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "report", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @ToString.Exclude
    private List<ExpenseLineItem> expenseLineItems = new ArrayList<>();

    @OneToMany(mappedBy = "report", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @ToString.Exclude
    private List<ApprovalTask> approvalTasks = new ArrayList<>();

    @OneToMany(mappedBy = "report")
    @Builder.Default
    @ToString.Exclude
    private List<CashAdvanceAdjustment> cashAdvanceAdjustments = new ArrayList<>();
}
