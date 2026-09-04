package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.entity.CdcFailureLog;
import com.expense_management_service.repository.CdcFailureLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CdcFailureLogServiceImplTest {

    @Mock
    private CdcFailureLogRepository cdcFailureLogRepository;

    private CdcFailureLogServiceImpl cdcFailureLogService;

    @BeforeEach
    void setUp() {
        cdcFailureLogService = new CdcFailureLogServiceImpl(cdcFailureLogRepository);
    }

    @Test
    void logFailure_savesRowWithFailedStatusAndZeroRetries() {
        when(cdcFailureLogRepository.save(any(CdcFailureLog.class))).thenAnswer(inv -> {
            CdcFailureLog saved = inv.getArgument(0);
            saved.setFailureId(UUID.randomUUID());
            return saved;
        });

        CdcFailureLog result = cdcFailureLogService.logFailure(
                "eos_dev.eos.employee_details", "5100101", "uuid-1", "c",
                "PARSE_FAILED", "boom", "{}", 0, 42L);

        assertThat(result.getStatus()).isEqualTo("FAILED");
        assertThat(result.getRetryCount()).isZero();
        assertThat(result.getMaxRetries()).isEqualTo(3);
        assertThat(result.getFailureType()).isEqualTo("PARSE_FAILED");
    }

    @Test
    void findRetryable_delegatesToRepositoryWithFailedAndRetryingStatuses() {
        CdcFailureLog failed = CdcFailureLog.builder().failureId(UUID.randomUUID()).status("FAILED").build();
        when(cdcFailureLogRepository.findByStatusInAndRetryCountLessThan(List.of("FAILED", "RETRYING"), 3))
                .thenReturn(List.of(failed));

        List<CdcFailureLog> result = cdcFailureLogService.findRetryable();

        assertThat(result).containsExactly(failed);
    }

    @Test
    void markRetrySucceeded_setsResolvedStatusAndResolvedAt() {
        UUID id = UUID.randomUUID();
        CdcFailureLog existing = CdcFailureLog.builder().failureId(id).status("RETRYING").retryCount(1).build();
        when(cdcFailureLogRepository.findById(id)).thenReturn(Optional.of(existing));
        when(cdcFailureLogRepository.save(any(CdcFailureLog.class))).thenAnswer(inv -> inv.getArgument(0));

        cdcFailureLogService.markRetrySucceeded(id);

        ArgumentCaptor<CdcFailureLog> captor = ArgumentCaptor.forClass(CdcFailureLog.class);
        org.mockito.Mockito.verify(cdcFailureLogRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("RESOLVED");
        assertThat(captor.getValue().getResolvedAt()).isNotNull();
    }

    @Test
    void markRetrySucceeded_throwsResourceNotFoundException_whenMissing() {
        UUID id = UUID.randomUUID();
        when(cdcFailureLogRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cdcFailureLogService.markRetrySucceeded(id))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void markRetryFailed_incrementsRetryCountAndSetsRetrying_whenUnderLimit() {
        UUID id = UUID.randomUUID();
        CdcFailureLog existing = CdcFailureLog.builder().failureId(id).status("FAILED").retryCount(0).maxRetries(3).build();
        when(cdcFailureLogRepository.findById(id)).thenReturn(Optional.of(existing));
        when(cdcFailureLogRepository.save(any(CdcFailureLog.class))).thenAnswer(inv -> inv.getArgument(0));

        cdcFailureLogService.markRetryFailed(id, "still broken");

        assertThat(existing.getRetryCount()).isEqualTo(1);
        assertThat(existing.getStatus()).isEqualTo("RETRYING");
        assertThat(existing.getLastRetriedAt()).isNotNull();
    }

    @Test
    void markRetryFailed_setsExhausted_whenRetryCountReachesMax() {
        UUID id = UUID.randomUUID();
        CdcFailureLog existing = CdcFailureLog.builder().failureId(id).status("RETRYING").retryCount(2).maxRetries(3).build();
        when(cdcFailureLogRepository.findById(id)).thenReturn(Optional.of(existing));
        when(cdcFailureLogRepository.save(any(CdcFailureLog.class))).thenAnswer(inv -> inv.getArgument(0));

        cdcFailureLogService.markRetryFailed(id, "still broken");

        assertThat(existing.getRetryCount()).isEqualTo(3);
        assertThat(existing.getStatus()).isEqualTo("EXHAUSTED");
    }
}
