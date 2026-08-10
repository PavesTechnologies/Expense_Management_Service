package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One employee's membership in one {@link PolicyGroup}. The unique constraint on {@code
 * employee_id} (not a composite with {@code group_id}) is what enforces "an employee belongs to at
 * most one policy-determining group at a time" - moving an employee to a different group is a
 * delete-then-insert, not an update, matching how {@code CostAllocation}-style join rows are
 * managed elsewhere in this codebase (hard delete on removal, no soft-delete status column here).
 */
@Entity
@Table(name = "policy_group_member", uniqueConstraints = @UniqueConstraint(name = "uk_policy_group_member_employee", columnNames = "employee_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class PolicyGroupMember {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "member_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID memberId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    @ToString.Exclude
    private PolicyGroup group;

    /** Raw UMS employee id - matches how employees are referenced everywhere else in this codebase (no local Employee entity/FK). */
    @Column(name = "employee_id", length = 255, nullable = false)
    private String employeeId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
