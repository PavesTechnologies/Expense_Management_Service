package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "approval_delegation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class ApprovalDelegation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "delegation_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID delegationId;

    @Column(name = "delegator_id", length = 255, nullable = false)
    private String delegatorId;

    @Column(name = "delegate_id", length = 255, nullable = false)
    private String delegateId;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "status", length = 255)
    private String status;
}
