package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "notification")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "notification_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID notificationId;

    @Column(name = "employee_id", length = 255, nullable = false)
    private String employeeId;

    @Column(name = "notification_type", length = 255)
    private String notificationType;

    @Column(name = "title", length = 255)
    private String title;

    @Lob
    @Column(name = "message")
    private String message;

    @Column(name = "is_read")
    private Boolean isRead;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;
}
