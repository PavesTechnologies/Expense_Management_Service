package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.DuplicateResourceException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.ExpenseCategoryRequest;
import com.expense_management_service.dto.response.ExpenseCategoryResponse;
import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.entity.GlAccount;
import com.expense_management_service.mapper.ExpenseCategoryMapper;
import com.expense_management_service.repository.ExpenseCategoryRepository;
import com.expense_management_service.repository.GlAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
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
class ExpenseCategoryServiceImplTest {

    @Mock
    private ExpenseCategoryRepository expenseCategoryRepository;

    @Mock
    private GlAccountRepository glAccountRepository;

    private ExpenseCategoryServiceImpl expenseCategoryService;

    @BeforeEach
    void setUp() {
        expenseCategoryService = new ExpenseCategoryServiceImpl(
                expenseCategoryRepository, glAccountRepository, new ExpenseCategoryMapper());
    }

    private static GlAccount activeGlAccount(UUID id) {
        return GlAccount.builder().glAccountId(id).glAccountCode("6000").glAccountName("Travel").status("ACTIVE").build();
    }

    private static ExpenseCategoryRequest validRequest(UUID glAccountId) {
        return new ExpenseCategoryRequest("TRAVEL", "Travel", glAccountId, "desc", true,
                null, "TX01", LocalDate.of(2026, 1, 1), null, "ACTIVE");
    }

    @Test
    void create_savesNewCategoryWithDefaultActiveStatus_whenValid() {
        UUID glAccountId = UUID.randomUUID();
        ExpenseCategoryRequest request = validRequest(glAccountId);

        when(expenseCategoryRepository.findByCategoryNameIgnoreCase("Travel")).thenReturn(Optional.empty());
        when(glAccountRepository.findById(glAccountId)).thenReturn(Optional.of(activeGlAccount(glAccountId)));
        when(expenseCategoryRepository.save(any(ExpenseCategory.class))).thenAnswer(invocation -> {
            ExpenseCategory saved = invocation.getArgument(0);
            saved.setCategoryId(UUID.randomUUID());
            return saved;
        });

        ExpenseCategoryResponse response = expenseCategoryService.create(request);

        assertThat(response.categoryName()).isEqualTo("Travel");
        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.glAccountId()).isEqualTo(glAccountId);
    }

    @Test
    void create_throwsDuplicateResourceException_whenNameAlreadyExists() {
        UUID glAccountId = UUID.randomUUID();
        ExpenseCategoryRequest request = validRequest(glAccountId);
        ExpenseCategory existing = ExpenseCategory.builder().categoryId(UUID.randomUUID()).categoryName("Travel").build();

        when(expenseCategoryRepository.findByCategoryNameIgnoreCase("Travel")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> expenseCategoryService.create(request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("Travel");

        verify(expenseCategoryRepository, never()).save(any());
    }

    @Test
    void create_throwsResourceNotFoundException_whenGlAccountMissing() {
        UUID glAccountId = UUID.randomUUID();
        ExpenseCategoryRequest request = validRequest(glAccountId);

        when(expenseCategoryRepository.findByCategoryNameIgnoreCase("Travel")).thenReturn(Optional.empty());
        when(glAccountRepository.findById(glAccountId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> expenseCategoryService.create(request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void create_throwsIllegalArgumentException_whenGlAccountInactive() {
        UUID glAccountId = UUID.randomUUID();
        ExpenseCategoryRequest request = validRequest(glAccountId);
        GlAccount inactive = GlAccount.builder().glAccountId(glAccountId).glAccountCode("6000").status("INACTIVE").build();

        when(expenseCategoryRepository.findByCategoryNameIgnoreCase("Travel")).thenReturn(Optional.empty());
        when(glAccountRepository.findById(glAccountId)).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> expenseCategoryService.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not Active");

        verify(expenseCategoryRepository, never()).save(any());
    }

    @Test
    void create_throwsIllegalArgumentException_whenEffectiveToBeforeEffectiveFrom() {
        UUID glAccountId = UUID.randomUUID();
        ExpenseCategoryRequest request = new ExpenseCategoryRequest("TRAVEL", "Travel", glAccountId, null, null,
                null, null, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 1, 1), "ACTIVE");

        when(expenseCategoryRepository.findByCategoryNameIgnoreCase("Travel")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> expenseCategoryService.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("effectiveTo");

        verify(expenseCategoryRepository, never()).save(any());
    }

    @Test
    void update_allowsCategoryToKeepItsOwnName() {
        UUID categoryId = UUID.randomUUID();
        UUID glAccountId = UUID.randomUUID();
        ExpenseCategory existing = ExpenseCategory.builder()
                .categoryId(categoryId).categoryCode("TRAVEL").categoryName("Travel").status("ACTIVE").build();
        ExpenseCategoryRequest request = validRequest(glAccountId);

        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(existing));
        when(expenseCategoryRepository.findByCategoryNameIgnoreCase("Travel")).thenReturn(Optional.of(existing));
        when(glAccountRepository.findById(glAccountId)).thenReturn(Optional.of(activeGlAccount(glAccountId)));
        when(expenseCategoryRepository.save(any(ExpenseCategory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ExpenseCategoryResponse response = expenseCategoryService.update(categoryId, request);

        assertThat(response.categoryName()).isEqualTo("Travel");
    }

    @Test
    void update_throwsDuplicateResourceException_whenNameBelongsToAnotherCategory() {
        UUID categoryId = UUID.randomUUID();
        UUID otherCategoryId = UUID.randomUUID();
        UUID glAccountId = UUID.randomUUID();
        ExpenseCategory existing = ExpenseCategory.builder().categoryId(categoryId).categoryName("Old").status("ACTIVE").build();
        ExpenseCategory other = ExpenseCategory.builder().categoryId(otherCategoryId).categoryName("Travel").status("ACTIVE").build();
        ExpenseCategoryRequest request = validRequest(glAccountId);

        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(existing));
        when(expenseCategoryRepository.findByCategoryNameIgnoreCase("Travel")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> expenseCategoryService.update(categoryId, request))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void getById_throwsResourceNotFoundException_whenMissing() {
        UUID categoryId = UUID.randomUUID();
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> expenseCategoryService.getById(categoryId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getActiveCategories_returnsOnlyActiveCategories() {
        ExpenseCategory category = ExpenseCategory.builder()
                .categoryId(UUID.randomUUID()).categoryName("Travel").status("ACTIVE").build();
        when(expenseCategoryRepository.findByStatusIgnoreCaseOrderByCategoryNameAsc("ACTIVE"))
                .thenReturn(List.of(category));

        List<ExpenseCategoryResponse> result = expenseCategoryService.getActiveCategories();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).categoryName()).isEqualTo("Travel");
    }

    @Test
    void delete_removesCategory_whenFound() {
        UUID categoryId = UUID.randomUUID();
        ExpenseCategory existing = ExpenseCategory.builder().categoryId(categoryId).categoryName("Travel").build();
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(existing));

        expenseCategoryService.delete(categoryId);

        verify(expenseCategoryRepository).delete(existing);
    }
}
