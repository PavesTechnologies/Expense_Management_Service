package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.dto.response.AssignedProjectResponse;
import com.expense_management_service.entity.ProjectCache;
import com.expense_management_service.integration.pms.PmsClient;
import com.expense_management_service.integration.pms.dto.PmsProjectDetailResponse;
import com.expense_management_service.integration.pms.dto.PmsProjectSummaryResponse;
import com.expense_management_service.repository.ProjectCacheRepository;
import com.expense_management_service.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the exact request path behind {@code GET /xms/employee/projects/assigned} that
 * previously shipped without any test at the service layer — only a controller test existed,
 * which mocks {@code EmployeeProjectService} entirely and therefore never exercised the real
 * userId-resolution / PMS-call / mapping logic where the actual bugs lived.
 */
@ExtendWith(MockitoExtension.class)
class EmployeeProjectServiceImplTest {

    @Mock
    private PmsClient pmsClient;
    @Mock
    private ProjectCacheRepository projectCacheRepository;
    @Mock
    private CurrentUserService currentUserService;

    private EmployeeProjectServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new EmployeeProjectServiceImpl(pmsClient, projectCacheRepository, currentUserService);
    }

    @Test
    void getAssignedProjects_resolvesCallerUmsUserId_andMapsPmsProjects() {
        when(currentUserService.getUmsUserId()).thenReturn(9001L);
        when(pmsClient.getMyActiveProjects(9001L)).thenReturn(List.of(
                new PmsProjectSummaryResponse(501L, null, "Alpha", null, null),
                new PmsProjectSummaryResponse(777L, null, "Beta", null, null)
        ));
        when(projectCacheRepository.findByPmsProjectId(any())).thenReturn(Optional.empty());
        when(projectCacheRepository.save(any(ProjectCache.class))).thenAnswer(inv -> {
            ProjectCache saved = inv.getArgument(0);
            saved.setProjectId(UUID.randomUUID());
            return saved;
        });

        List<AssignedProjectResponse> result = service.getAssignedProjects();

        assertThat(result).hasSize(2);
        assertThat(result).extracting(AssignedProjectResponse::projectName).containsExactly("Alpha", "Beta");
        // The UMS user id passed to PMS always comes from the caller's own validated JWT — never
        // anything externally suppliable, so one employee can never request another's projects.
        verify(pmsClient).getMyActiveProjects(9001L);
    }

    @Test
    void getAssignedProjects_returnsEmptyList_whenPmsHasNoAssignedWork() {
        when(currentUserService.getUmsUserId()).thenReturn(9002L);
        when(pmsClient.getMyActiveProjects(9002L)).thenReturn(List.of());

        assertThat(service.getAssignedProjects()).isEmpty();
        verify(projectCacheRepository, never()).save(any());
    }

    @Test
    void getAssignedProjects_propagates_whenUmsUserIdCannotBeResolved() {
        when(currentUserService.getUmsUserId())
                .thenThrow(new IllegalStateException("The current JWT does not carry a numeric 'user_id' claim"));

        assertThatThrownBy(() -> service.getAssignedProjects())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("user_id");

        verify(pmsClient, never()).getMyActiveProjects(any());
    }

    @Test
    void getAssignedProjects_propagates_whenPmsAuthenticationFails() {
        when(currentUserService.getUmsUserId()).thenReturn(9003L);
        when(pmsClient.getMyActiveProjects(9003L))
                .thenThrow(HttpClientErrorException.create(
                        org.springframework.http.HttpStatus.FORBIDDEN, "Forbidden", null, null, null));

        // Never silently converted into an empty (successful-looking) project list.
        assertThatThrownBy(() -> service.getAssignedProjects()).isInstanceOf(HttpClientErrorException.class);
    }

    @Test
    void getAssignedProjects_propagates_whenPmsServerErrors() {
        when(currentUserService.getUmsUserId()).thenReturn(9004L);
        when(pmsClient.getMyActiveProjects(9004L))
                .thenThrow(HttpServerErrorException.create(
                        org.springframework.http.HttpStatus.BAD_GATEWAY, "Bad Gateway", null, null, null));

        assertThatThrownBy(() -> service.getAssignedProjects()).isInstanceOf(HttpServerErrorException.class);
    }

    @Test
    void getAssignedProjects_upsertsExistingCacheRow_byPmsProjectId_notDuplicating() {
        UUID existingId = UUID.randomUUID();
        ProjectCache existing = ProjectCache.builder().projectId(existingId).pmsProjectId(501L)
                .projectCode("OLD-CODE").projectName("Old Name").status("ACTIVE").build();

        when(currentUserService.getUmsUserId()).thenReturn(9005L);
        when(pmsClient.getMyActiveProjects(9005L)).thenReturn(List.of(new PmsProjectSummaryResponse(501L, null, "New Name", null, null)));
        when(projectCacheRepository.findByPmsProjectId(501L)).thenReturn(Optional.of(existing));
        when(projectCacheRepository.save(any(ProjectCache.class))).thenAnswer(inv -> inv.getArgument(0));

        List<AssignedProjectResponse> result = service.getAssignedProjects();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).projectId()).isEqualTo(existingId);
        assertThat(result.get(0).projectName()).isEqualTo("New Name");
    }

}
