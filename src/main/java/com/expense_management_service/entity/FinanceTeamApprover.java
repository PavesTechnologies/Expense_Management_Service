package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Admin-curated mapping of cost center &rarr; Finance approver, backing {@code
 * ApproverSourceType.FINANCE_OWNER}. Keyed by cost center (not department, unlike {@link
 * DepartmentApprover}) because Finance Verification is about who owns the GL/ledger postings for a
 * report's cost center, not the submitter's org placement.
 * <p>
 * <b>Known limitation:</b> {@link #approverEmployeeId} is trusted, admin-typed input - nothing in
 * XMS cross-checks it against UMS to confirm that employee actually holds the {@code
 * FINANCE_EXECUTIVE} role (no UMS API exists for "does employeeId X have role Y" that XMS can
 * safely call - {@code UmsClient}'s endpoints are keyed by UMS UUID and aren't confirmed against
 * the real integration spec; inventing one was explicitly out of scope). The safest existing
 * mechanism is administrative discipline (only configure this field with employeeIds that hold
 * {@code FINANCE_EXECUTIVE} in UMS) backed by defense-in-depth in the authorization layer: {@code
 * FinanceVerificationController}'s {@code @PreAuthorize} requires the *acting* caller to hold
 * {@code FINANCE_EXECUTIVE} regardless of who's configured here, so a misconfigured mapping fails
 * safe (the level becomes unactionable by anyone) rather than failing open.
 */
@Entity
@Table(name = "finance_team_approver", uniqueConstraints = {
        @UniqueConstraint(columnNames = "cost_center_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class FinanceTeamApprover {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "finance_team_approver_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID financeTeamApproverId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cost_center_id", nullable = false)
    @ToString.Exclude
    private CostCenter costCenter;

    /** EOS employeeId of the Finance approver for this cost center. */
    @Column(name = "approver_employee_id", length = 255, nullable = false)
    private String approverEmployeeId;

    @Column(name = "status", length = 255)
    private String status;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
