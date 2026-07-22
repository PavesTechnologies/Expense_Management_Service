package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "verification_query")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class VerificationQuery {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "query_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID queryId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "line_item_id", nullable = false)
    @ToString.Exclude
    private ExpenseLineItem lineItem;

    @Column(name = "raised_by", length = 255, nullable = false)
    private String raisedBy;

    @Lob
    @Column(name = "query_text", nullable = false)
    private String queryText;

    @Lob
    @Column(name = "employee_response")
    private String employeeResponse;

    @Column(name = "status", length = 255)
    private String status;

    @Column(name = "raised_at")
    private LocalDateTime raisedAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;
}
