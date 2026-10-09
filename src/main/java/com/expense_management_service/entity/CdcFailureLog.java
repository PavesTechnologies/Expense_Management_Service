package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Dead-letter record for an Employee CDC event that could not be processed.
 *
 * <p>Mirrors the shape of the Leave Management System's {@code cdc_failure_log}
 * table, with one deliberate difference: in LMS, this table is written by
 * nothing (its consumer only logs and acknowledges), so its retry cron always
 * finds an empty table. {@code consumer.EmployeeCdcConsumer} in this service
 * writes a row here on every failure path, before acknowledging the message,
 * so retries actually have something to act on.</p>
 */
@Entity
@Table(name = "cdc_failure_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class CdcFailureLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "failure_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID failureId;

    /** Kafka topic the failed message came from, e.g. "eos_dev.eos.employee_details". */
    @Column(name = "source_topic", length = 255, nullable = false)
    private String sourceTopic;

    /** Populated when the payload could be parsed far enough to extract an identifier. */
    @Column(name = "employee_id", length = 20)
    private String employeeId;

    @Column(name = "employee_uuid", length = 36)
    private String employeeUuid;

    /** The Debezium __op value if known: "c", "u", "d", or "unknown" if unparseable. */
    @Column(name = "operation", length = 20)
    private String operation;

    /** One of PARSE_FAILED, UPSERT_FAILED, DELETE_FAILED, UNKNOWN_STATUS, VALIDATION_FAILED. */
    @Column(name = "failure_type", length = 50, nullable = false)
    private String failureType;

    // columnDefinition is explicit on both LOB fields below: @Lob alone maps to MySQL
    // TINYTEXT (255-byte max, inherited from JPA's default @Column(length=255)) unless
    // told otherwise - discovered by actually running this against MySQL, where a small
    // real payload triggered "Data too long for column 'raw_payload'".
    @Lob
    @Column(name = "error_message", columnDefinition = "LONGTEXT")
    private String errorMessage;

    /** The raw Kafka record value, kept so a retry can replay it without re-consuming. */
    @Lob
    @Column(name = "raw_payload", columnDefinition = "LONGTEXT")
    private String rawPayload;

    /** One of FAILED, RETRYING, RESOLVED, EXHAUSTED. */
    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "max_retries", nullable = false)
    @Builder.Default
    private Integer maxRetries = 3;

    @Column(name = "kafka_partition")
    private Integer kafkaPartition;

    @Column(name = "kafka_offset")
    private Long kafkaOffset;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_retried_at")
    private LocalDateTime lastRetriedAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;
}
