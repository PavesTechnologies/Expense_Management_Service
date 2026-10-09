package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A named, reusable bundle of {@link PolicyRule}s (e.g. "Field Sales Policy", "Default Policy").
 * Every {@code PolicyRule} belongs to exactly one {@code Policy} via {@link PolicyRule#getPolicy()}.
 * Introduced so an employee can eventually be governed by one policy among several — see
 * {@link com.expense_management_service.entity.PolicyAssignment} for how a policy is connected to
 * an employee.
 */
@Entity
@Table(name = "policy", uniqueConstraints = @UniqueConstraint(name = "uk_policy_name", columnNames = "policy_name"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class Policy {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "policy_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID policyId;

    @Column(name = "policy_name", length = 255, nullable = false)
    private String policyName;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "status", length = 255)
    private String status;

    @Version
    @Column(name = "version")
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "policy")
    @Builder.Default
    @ToString.Exclude
    private List<PolicyRule> rules = new ArrayList<>();
}
