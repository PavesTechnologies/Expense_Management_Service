package com.expense_management_service.entity;

import com.expense_management_service.enums.AuditSource;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "audit_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID auditId;

    @Column(name = "entity_name", length = 255, nullable = false)
    private String entityName;

    @Column(name = "entity_id")
    private UUID entityId;

    @Column(name = "action", length = 255, nullable = false)
    private String action;

    @Lob
    @Column(name = "old_value", columnDefinition = "LONGTEXT")
    private String oldValue;

    @Lob
    @Column(name = "new_value", columnDefinition = "LONGTEXT")
    private String newValue;

    @Column(name = "performed_by", length = 255)
    private String performedBy;

    @Column(name = "performed_at")
    private LocalDateTime performedAt;

    /** Why the change was made; required for tax overrides, Finance adjustments and deactivations. */
    @Column(name = "reason", length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 16)
    private AuditSource source;
}
