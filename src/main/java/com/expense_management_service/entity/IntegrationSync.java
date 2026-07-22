package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "integration_sync")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class IntegrationSync {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "integration_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID integrationId;

    @Column(name = "integration_name", length = 255, nullable = false)
    private String integrationName;

    @Column(name = "reference_id")
    private UUID referenceId;

    @Lob
    @Column(name = "request_payload")
    private String requestPayload;

    @Lob
    @Column(name = "response_payload")
    private String responsePayload;

    @Column(name = "sync_status", length = 255)
    private String syncStatus;

    @Column(name = "retry_count")
    private Integer retryCount;

    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;
}
