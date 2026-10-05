package com.expense_management_service.service.impl;

import com.expense_management_service.entity.Notification;
import com.expense_management_service.entity.NotificationRead;
import com.expense_management_service.mapper.NotificationMapper;
import com.expense_management_service.repository.NotificationReadRepository;
import com.expense_management_service.repository.NotificationRepository;
import com.expense_management_service.security.CurrentUser;
import com.expense_management_service.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceImplTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private NotificationReadRepository notificationReadRepository;
    @Mock private CurrentUserService currentUserService;
    @Mock private SimpMessagingTemplate messagingTemplate;

    private NotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new NotificationServiceImpl(notificationRepository, notificationReadRepository, new NotificationMapper(),
                currentUserService, messagingTemplate);
        when(currentUserService.getEmployeeId()).thenReturn("5100050");
    }

    private void callerRoles(String... roles) {
        when(currentUserService.getCurrentUser()).thenReturn(
                new CurrentUser(UUID.randomUUID(), null, "5100050", "f@example.com", "Fin Exec", List.of(roles), List.of()));
    }

    @SuppressWarnings("unchecked")
    @Test
    void search_scopesToCallerAndOnlyTheirTeamInboxes_andResolvesTeamReadState() {
        callerRoles("General", "Finance_Executive");
        UUID teamId = UUID.randomUUID();
        Notification team = Notification.builder().notificationId(teamId).recipientRole("FINANCE_EXECUTIVE").title("t").sentAt(LocalDateTime.now()).build();
        Notification mine = Notification.builder().notificationId(UUID.randomUUID()).employeeId("5100050").isRead(true).title("m").sentAt(LocalDateTime.now()).build();
        when(notificationRepository.search(eq("5100050"), any(), any(), any(), any(), any(), any(), eq("all"), any()))
                .thenReturn(new PageImpl<>(List.of(team, mine)));
        when(notificationReadRepository.findReadIds(eq("5100050"), any())).thenReturn(Set.of(teamId));

        var page = service.search("all", null, null, null, null, null, 0, 10);

        ArgumentCaptor<Collection<String>> roles = ArgumentCaptor.forClass(Collection.class);
        verify(notificationRepository).search(eq("5100050"), roles.capture(), any(), any(), any(), any(), any(), eq("all"), any());
        assertThat(roles.getValue()).containsExactly("FINANCE_EXECUTIVE");
        assertThat(page.content()).extracting(n -> n.read()).containsExactly(true, true);
    }

    @SuppressWarnings("unchecked")
    @Test
    void userWithNoTeamRole_getsAPlaceholderRole_soTheQueryStaysValid() {
        callerRoles("General");
        when(notificationRepository.countUnread(eq("5100050"), any())).thenReturn(3L);

        assertThat(service.unreadCount()).isEqualTo(3);
        ArgumentCaptor<Collection<String>> roles = ArgumentCaptor.forClass(Collection.class);
        verify(notificationRepository).countUnread(eq("5100050"), roles.capture());
        assertThat(roles.getValue()).containsExactly("__NONE__");
    }

    @Test
    void markRead_onSomeoneElsesPersonalNotification_isDenied() {
        callerRoles("General");
        UUID id = UUID.randomUUID();
        when(notificationRepository.findById(id)).thenReturn(Optional.of(Notification.builder().notificationId(id).employeeId("5100099").build()));

        assertThatThrownBy(() -> service.markRead(id)).isInstanceOf(AccessDeniedException.class);
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void markRead_onTeamNotification_recordsAPerPersonReadMarker() {
        callerRoles("Finance_Executive");
        UUID id = UUID.randomUUID();
        when(notificationRepository.findById(id)).thenReturn(Optional.of(Notification.builder().notificationId(id).recipientRole("FINANCE_EXECUTIVE").build()));
        when(notificationReadRepository.existsById(any())).thenReturn(false);

        service.markRead(id);

        ArgumentCaptor<NotificationRead> read = ArgumentCaptor.forClass(NotificationRead.class);
        verify(notificationReadRepository).save(read.capture());
        assertThat(read.getValue().getNotificationId()).isEqualTo(id);
        assertThat(read.getValue().getEmployeeId()).isEqualTo("5100050");
    }

    @Test
    void markRead_onAnotherTeamsNotification_isDenied() {
        callerRoles("Finance_Executive");
        UUID id = UUID.randomUUID();
        when(notificationRepository.findById(id)).thenReturn(Optional.of(Notification.builder().notificationId(id).recipientRole("AP_EXECUTIVE").build()));

        assertThatThrownBy(() -> service.markRead(id)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void superAdmin_readsTheAdminInbox() {
        callerRoles("Super_Admin");
        when(notificationRepository.findUnreadTeamIds(eq("5100050"), any())).thenReturn(List.of());
        when(notificationRepository.markAllPersonalRead("5100050")).thenReturn(0);

        service.markAllRead();

        verify(notificationRepository).findUnreadTeamIds(eq("5100050"), eq(Set.of("ADMIN")));
    }

    @Test
    void notifyEmployee_savesAndPushesToTheirQueue() {
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        service.notifyEmployee("5100014", com.expense_management_service.service.NotificationDraft.builder()
                .title("Hello").eventType("REPORT_SUBMITTED").build());

        verify(messagingTemplate).convertAndSendToUser(eq("5100014"), eq("/queue/notifications"), any(Object.class));
        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
    }
}
