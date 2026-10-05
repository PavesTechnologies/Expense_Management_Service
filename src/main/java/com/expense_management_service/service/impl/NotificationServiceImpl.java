package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.response.NotificationResponse;
import com.expense_management_service.dto.response.PageResponse;
import com.expense_management_service.entity.Notification;
import com.expense_management_service.entity.NotificationRead;
import com.expense_management_service.enums.NotificationCategory;
import com.expense_management_service.mapper.NotificationMapper;
import com.expense_management_service.repository.NotificationReadRepository;
import com.expense_management_service.repository.NotificationRepository;
import com.expense_management_service.security.CurrentUser;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.service.NotificationDraft;
import com.expense_management_service.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class NotificationServiceImpl implements NotificationService {

    /** Personal notifications: {@code /user/queue/notifications}. */
    public static final String USER_QUEUE = "/queue/notifications";
    /** Team inboxes: {@code /topic/notifications.<ROLE>} (subscription restricted to holders of that role). */
    public static final String ROLE_TOPIC_PREFIX = "/topic/notifications.";

    /** Never matches a real role - keeps "in :roles" valid for users with no team inbox. */
    private static final String NO_ROLE = "__NONE__";
    private static final Set<String> TEAM_ROLES = Set.of(ROLE_FINANCE, ROLE_AP, ROLE_ADMIN);

    private final NotificationRepository notificationRepository;
    private final NotificationReadRepository notificationReadRepository;
    private final NotificationMapper notificationMapper;
    private final CurrentUserService currentUserService;
    private final SimpMessagingTemplate messagingTemplate;

    // ------------------------------------------------------------------ producing

    @Override
    public void notifyEmployee(String employeeId, NotificationDraft draft) {
        if (!StringUtils.hasText(employeeId)) return;
        Notification saved = notificationRepository.save(build(draft).employeeId(employeeId).isRead(false).build());
        afterCommit(() -> messagingTemplate.convertAndSendToUser(employeeId, USER_QUEUE, notificationMapper.toResponse(saved, false)));
    }

    @Override
    public void notifyRole(String role, NotificationDraft draft) {
        String normalized = role.toUpperCase(Locale.ROOT);
        Notification saved = notificationRepository.save(build(draft).recipientRole(normalized).build());
        afterCommit(() -> messagingTemplate.convertAndSend(ROLE_TOPIC_PREFIX + normalized, notificationMapper.toResponse(saved, false)));
    }

    private Notification.NotificationBuilder build(NotificationDraft d) {
        return Notification.builder()
                .category(d.category())
                .eventType(d.eventType())
                .notificationType(d.eventType())
                .title(d.title())
                .message(d.message())
                .reportId(d.reportId())
                .reportNumber(d.reportNumber())
                .actorName(d.actorName())
                .amount(d.amount())
                .currencyCode(d.currencyCode())
                .statusLabel(d.statusLabel())
                .actionLabel(d.actionLabel())
                .link(d.link())
                .sentAt(LocalDateTime.now());
    }

    /** The push goes out only once the notification row is committed, so a client refetch finds it. */
    private void afterCommit(Runnable push) {
        Runnable safe = () -> {
            try {
                push.run();
            } catch (Exception ex) {
                log.warn("Live notification push failed - the recipient will see it on next fetch", ex);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safe.run();
                }
            });
        } else {
            safe.run();
        }
    }

    // ------------------------------------------------------------------ reading

    @Override
    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> search(String readFilter, NotificationCategory category, String eventType,
                                                     LocalDate from, LocalDate to, String q, int page, int size) {
        String me = currentUserService.getEmployeeId();
        Set<String> roles = teamRoles();
        String filter = readFilter == null ? "all" : readFilter.toLowerCase(Locale.ROOT);
        if (!Set.of("all", "read", "unread").contains(filter)) {
            throw new IllegalArgumentException("readFilter must be all, read or unread");
        }
        Page<Notification> result = notificationRepository.search(me, roles, category,
                StringUtils.hasText(eventType) ? eventType : null,
                from != null ? from.atStartOfDay() : null,
                to != null ? to.plusDays(1).atStartOfDay() : null,
                StringUtils.hasText(q) ? "%" + q.trim().toLowerCase(Locale.ROOT) + "%" : null,
                filter, PageRequest.of(page, size));

        List<UUID> teamIds = result.getContent().stream()
                .filter(n -> n.getRecipientRole() != null).map(Notification::getNotificationId).toList();
        Set<UUID> readTeamIds = teamIds.isEmpty() ? Set.of() : notificationReadRepository.findReadIds(me, teamIds);

        return PageResponse.of(result.map(n -> notificationMapper.toResponse(n,
                n.getRecipientRole() != null ? readTeamIds.contains(n.getNotificationId()) : Boolean.TRUE.equals(n.getIsRead()))));
    }

    @Override
    @Transactional(readOnly = true)
    public long unreadCount() {
        return notificationRepository.countUnread(currentUserService.getEmployeeId(), teamRoles());
    }

    @Override
    public void markRead(UUID notificationId) {
        String me = currentUserService.getEmployeeId();
        Notification n = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found with id: " + notificationId));
        if (n.getEmployeeId() != null) {
            if (!n.getEmployeeId().equals(me)) throw new AccessDeniedException("Not your notification");
            n.setIsRead(true);
            notificationRepository.save(n);
        } else {
            if (!teamRoles().contains(n.getRecipientRole())) throw new AccessDeniedException("Not your notification");
            if (!notificationReadRepository.existsById(new NotificationRead.Key(notificationId, me))) {
                notificationReadRepository.save(new NotificationRead(notificationId, me, LocalDateTime.now()));
            }
        }
    }

    @Override
    public int markAllRead() {
        String me = currentUserService.getEmployeeId();
        int personal = notificationRepository.markAllPersonalRead(me);
        LocalDateTime now = LocalDateTime.now();
        List<NotificationRead> team = notificationRepository.findUnreadTeamIds(me, teamRoles()).stream()
                .map(id -> new NotificationRead(id, me, now)).toList();
        notificationReadRepository.saveAll(team);
        return personal + team.size();
    }

    /** The caller's team inboxes, from their JWT roles (SUPER_ADMIN reads the ADMIN inbox). */
    private Set<String> teamRoles() {
        CurrentUser user = currentUserService.getCurrentUser();
        Set<String> roles = new HashSet<>();
        if (user.roles() != null) {
            user.roles().forEach(r -> {
                String upper = r.toUpperCase(Locale.ROOT);
                if (TEAM_ROLES.contains(upper)) roles.add(upper);
                if ("SUPER_ADMIN".equals(upper)) roles.add(ROLE_ADMIN);
            });
        }
        if (roles.isEmpty()) roles.add(NO_ROLE);
        return roles;
    }
}
