package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.DuplicateResourceException;
import com.expense_management_service.common.exception.ResourceInUseException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.GlAccountRequest;
import com.expense_management_service.dto.response.GlAccountResponse;
import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.entity.GlAccount;
import com.expense_management_service.mapper.GlAccountMapper;
import com.expense_management_service.repository.ExpenseCategoryRepository;
import com.expense_management_service.repository.GlAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlAccountServiceImplTest {

    @Mock
    private GlAccountRepository glAccountRepository;

    @Mock
    private ExpenseCategoryRepository expenseCategoryRepository;

    private GlAccountServiceImpl glAccountService;

    @BeforeEach
    void setUp() {
        glAccountService = new GlAccountServiceImpl(glAccountRepository, expenseCategoryRepository, new GlAccountMapper());
    }

    @Test
    void create_savesNewAccountWithDefaultActiveStatus_whenCodeIsUnique() {
        GlAccountRequest request = new GlAccountRequest("6000", "Travel Expense", "EXPENSE", "desc", null);
        when(glAccountRepository.findByGlAccountCodeIgnoreCase("6000")).thenReturn(Optional.empty());
        when(glAccountRepository.save(any(GlAccount.class))).thenAnswer(invocation -> {
            GlAccount saved = invocation.getArgument(0);
            saved.setGlAccountId(UUID.randomUUID());
            return saved;
        });

        GlAccountResponse response = glAccountService.create(request);

        assertThat(response.glAccountCode()).isEqualTo("6000");
        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.mappedCategoryCount()).isZero();
    }

    @Test
    void create_throwsDuplicateResourceException_whenCodeAlreadyExistsCaseInsensitive() {
        GlAccountRequest request = new GlAccountRequest("6000", "Travel Expense", "EXPENSE", null, "ACTIVE");
        GlAccount existing = GlAccount.builder().glAccountId(UUID.randomUUID()).glAccountCode("6000").build();
        when(glAccountRepository.findByGlAccountCodeIgnoreCase("6000")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> glAccountService.create(request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("6000");

        verify(glAccountRepository, never()).save(any());
    }

    @Test
    void update_allowsAccountToKeepItsOwnCode() {
        UUID id = UUID.randomUUID();
        GlAccount existing = GlAccount.builder()
                .glAccountId(id).glAccountCode("6000").glAccountName("Old").status("ACTIVE").build();
        GlAccountRequest request = new GlAccountRequest("6000", "New Name", "EXPENSE", null, "ACTIVE");

        when(glAccountRepository.findById(id)).thenReturn(Optional.of(existing));
        when(glAccountRepository.findByGlAccountCodeIgnoreCase("6000")).thenReturn(Optional.of(existing));
        when(glAccountRepository.save(any(GlAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(expenseCategoryRepository.countByGlAccount_GlAccountId(id)).thenReturn(0L);

        GlAccountResponse response = glAccountService.update(id, request);

        assertThat(response.glAccountName()).isEqualTo("New Name");
    }

    @Test
    void update_throwsDuplicateResourceException_whenCodeBelongsToAnotherAccount() {
        UUID id = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        GlAccount existing = GlAccount.builder().glAccountId(id).glAccountCode("6000").status("ACTIVE").build();
        GlAccount other = GlAccount.builder().glAccountId(otherId).glAccountCode("7000").status("ACTIVE").build();
        GlAccountRequest request = new GlAccountRequest("7000", "Name", "EXPENSE", null, "ACTIVE");

        when(glAccountRepository.findById(id)).thenReturn(Optional.of(existing));
        when(glAccountRepository.findByGlAccountCodeIgnoreCase("7000")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> glAccountService.update(id, request))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void update_flagsMappedActiveCategoriesAsGlMappingInvalid_whenDeactivated() {
        UUID id = UUID.randomUUID();
        GlAccount existing = GlAccount.builder().glAccountId(id).glAccountCode("6000").status("ACTIVE").build();
        GlAccountRequest request = new GlAccountRequest("6000", "Name", "EXPENSE", null, "INACTIVE");

        ExpenseCategory mappedActiveCategory = ExpenseCategory.builder()
                .categoryId(UUID.randomUUID()).categoryName("Airfare").status("ACTIVE").build();

        when(glAccountRepository.findById(id)).thenReturn(Optional.of(existing));
        when(glAccountRepository.findByGlAccountCodeIgnoreCase("6000")).thenReturn(Optional.of(existing));
        when(glAccountRepository.save(any(GlAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(expenseCategoryRepository.findByGlAccount_GlAccountIdAndStatusIgnoreCase(id, "ACTIVE"))
                .thenReturn(List.of(mappedActiveCategory));
        when(expenseCategoryRepository.countByGlAccount_GlAccountId(id)).thenReturn(1L);

        glAccountService.update(id, request);

        assertThat(mappedActiveCategory.getStatus()).isEqualTo("GL_MAPPING_INVALID");
        verify(expenseCategoryRepository).saveAll(List.of(mappedActiveCategory));
    }

    @Test
    void delete_throwsResourceInUseException_whenMappedToExpenseCategories() {
        UUID id = UUID.randomUUID();
        GlAccount existing = GlAccount.builder().glAccountId(id).glAccountCode("6000").build();
        ExpenseCategory mapped = ExpenseCategory.builder().categoryId(UUID.randomUUID()).categoryName("Airfare").build();

        when(glAccountRepository.findById(id)).thenReturn(Optional.of(existing));
        when(expenseCategoryRepository.findByGlAccount_GlAccountId(id)).thenReturn(List.of(mapped));

        assertThatThrownBy(() -> glAccountService.delete(id))
                .isInstanceOf(ResourceInUseException.class)
                .hasMessageContaining("Airfare");

        verify(glAccountRepository, never()).delete(any());
    }

    @Test
    void delete_succeeds_whenNoExpenseCategoriesMapped() {
        UUID id = UUID.randomUUID();
        GlAccount existing = GlAccount.builder().glAccountId(id).glAccountCode("6000").build();

        when(glAccountRepository.findById(id)).thenReturn(Optional.of(existing));
        when(expenseCategoryRepository.findByGlAccount_GlAccountId(id)).thenReturn(List.of());

        glAccountService.delete(id);

        verify(glAccountRepository).delete(existing);
    }

    @Test
    void getById_throwsResourceNotFoundException_whenMissing() {
        UUID id = UUID.randomUUID();
        when(glAccountRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> glAccountService.getById(id))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getActiveAccounts_returnsOnlyActiveAccountsWithMappedCategoryCount() {
        GlAccount account = GlAccount.builder()
                .glAccountId(UUID.randomUUID()).glAccountCode("6000").glAccountName("Travel").status("ACTIVE").build();
        when(glAccountRepository.findByStatusIgnoreCaseOrderByGlAccountNameAsc("ACTIVE")).thenReturn(List.of(account));
        when(expenseCategoryRepository.countByGlAccount_GlAccountId(account.getGlAccountId())).thenReturn(2L);

        List<GlAccountResponse> result = glAccountService.getActiveAccounts();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).mappedCategoryCount()).isEqualTo(2L);
    }
}
