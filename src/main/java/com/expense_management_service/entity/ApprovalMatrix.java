package com.expense_management_service.entity;

import com.expense_management_service.enums.ApprovalMode;
import com.expense_management_service.enums.ApproverType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "approval_matrix")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class ApprovalMatrix {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "matrix_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID matrixId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cost_center_id", nullable = false)
    @ToString.Exclude
    private CostCenter costCenter;

    @Column(name = "minimum_amount", precision = 19, scale = 4)
    private BigDecimal minimumAmount;

    @Column(name = "maximum_amount", precision = 19, scale = 4)
    private BigDecimal maximumAmount;

    @Column(name = "approval_level")
    private Integer approvalLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "approver_type", length = 255)
    private ApproverType approverType;

    @Column(name = "approver_reference", length = 255)
    private String approverReference;

    /** Sequential vs. parallel-any vs. parallel-all for this level (see EP06 plan, S1). */
    @Enumerated(EnumType.STRING)
    @Column(name = "approval_mode", length = 255)
    private ApprovalMode approvalMode;

    @Column(name = "status", length = 255)
    private String status;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
