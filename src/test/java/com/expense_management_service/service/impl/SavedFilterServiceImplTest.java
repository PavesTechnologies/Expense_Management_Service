package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.DuplicateResourceException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.SavedFilterRequest;
import com.expense_management_service.entity.SavedFilter;
import com.expense_management_service.mapper.SavedFilterMapper;
import com.expense_management_service.repository.SavedFilterRepository;
import com.expense_management_service.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class SavedFilterServiceImplTest {

    private static final String ME = "5100001";

    @Mock private SavedFilterRepository savedFilterRepository;
    @Mock private CurrentUserService currentUserService;

    private SavedFilterServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SavedFilterServiceImpl(savedFilterRepository, new SavedFilterMapper(), currentUserService);
        when(currentUserService.getEmployeeId()).thenReturn(ME);
        when(savedFilterRepository.save(any(SavedFilter.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void create_ownsTheFilterAsTheCaller() {
        when(savedFilterRepository.findByEmployeeIdAndFilterNameIgnoreCase(ME, "Pending")).thenReturn(Optional.empty());

        service.create(new SavedFilterRequest("  Pending ", "{\"scope\":\"my-expenses\"}"));

        ArgumentCaptor<SavedFilter> saved = ArgumentCaptor.forClass(SavedFilter.class);
        verify(savedFilterRepository).save(saved.capture());
        assertThat(saved.getValue().getEmployeeId()).isEqualTo(ME);
        assertThat(saved.getValue().getFilterName()).isEqualTo("Pending");
    }

    @Test
    void create_rejectsADuplicateNameForTheSameEmployee() {
        when(savedFilterRepository.findByEmployeeIdAndFilterNameIgnoreCase(ME, "Pending"))
                .thenReturn(Optional.of(SavedFilter.builder().filterId(UUID.randomUUID()).employeeId(ME).filterName("pending").build()));

        assertThatThrownBy(() -> service.create(new SavedFilterRequest("Pending", "{}")))
                .isInstanceOf(DuplicateResourceException.class);
        verify(savedFilterRepository, never()).save(any());
    }

    @Test
    void getAll_returnsOnlyTheCallersFilters() {
        when(savedFilterRepository.findByEmployeeIdOrderByFilterNameAsc(ME))
                .thenReturn(List.of(SavedFilter.builder().filterId(UUID.randomUUID()).employeeId(ME).filterName("A").build()));

        assertThat(service.getAll()).extracting("employeeId").containsOnly(ME);
    }

    @Test
    void anotherEmployeesFilter_isNotFound_forReadUpdateOrDelete() {
        UUID id = UUID.randomUUID();
        when(savedFilterRepository.findById(id))
                .thenReturn(Optional.of(SavedFilter.builder().filterId(id).employeeId("5100999").filterName("Theirs").build()));

        assertThatThrownBy(() -> service.getById(id)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.update(id, new SavedFilterRequest("Mine now", "{}"))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(ResourceNotFoundException.class);
        verify(savedFilterRepository, never()).delete(any());
    }
}
