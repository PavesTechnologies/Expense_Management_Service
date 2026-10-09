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
 * A curated, single-purpose membership list Admin builds deliberately (e.g. "Senior Engineers -
 * Development") - not a live filter on department/role/seniority that could overlap
 * unpredictably. An employee belongs to at most one policy-determining group at a time, enforced
 * by the unique {@code employee_id} index on {@link PolicyGroupMember}, not here.
 */
@Entity
@Table(name = "policy_group", uniqueConstraints = @UniqueConstraint(name = "uk_policy_group_name", columnNames = "group_name"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class PolicyGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "group_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID groupId;

    @Column(name = "group_name", length = 255, nullable = false)
    private String groupName;

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

    @OneToMany(mappedBy = "group")
    @Builder.Default
    @ToString.Exclude
    private List<PolicyGroupMember> members = new ArrayList<>();
}
