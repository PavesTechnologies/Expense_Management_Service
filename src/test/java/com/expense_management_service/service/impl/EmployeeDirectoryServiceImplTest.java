package com.expense_management_service.service.impl;

import com.expense_management_service.entity.EmployeeCache;
import com.expense_management_service.repository.EmployeeCacheRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmployeeDirectoryServiceImplTest {

    @Mock private EmployeeCacheRepository employeeCacheRepository;

    @Test
    void getActiveEmployees_sortsByName_andSkipsRowsWithoutAnEmployeeId() {
        when(employeeCacheRepository.findByEmploymentStatusIgnoreCase("Active")).thenReturn(List.of(
                EmployeeCache.builder().employeeId("5100003").firstName("zara").lastName("Khan").workEmail("z@x.com").build(),
                EmployeeCache.builder().employeeId(" ").firstName("No").lastName("Id").build(),
                EmployeeCache.builder().employeeId("5100002").firstName("Asha").build(),
                EmployeeCache.builder().employeeId("5100004").build()));

        var employees = new EmployeeDirectoryServiceImpl(employeeCacheRepository).getActiveEmployees();

        assertThat(employees).extracting("employeeId").containsExactly("5100004", "5100002", "5100003");
        assertThat(employees).extracting("name").containsExactly("5100004", "Asha", "zara Khan");
    }
}
