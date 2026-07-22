package com.expense_management_service.entity;

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

    @Column(name = "approver_type", length = 255)
    private String approverType;

    @Column(name = "approver_reference", length = 255)
    private String approverReference;

    @Column(name = "status", length = 255)
    private String status;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
