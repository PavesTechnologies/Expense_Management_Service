package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.response.ClientResponse;
import com.expense_management_service.service.ClientService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * RMS-backed client lookup. Clients are owned by RMS, not XMS — this is a read-through to RMS,
 * not an admin master (no create/update/delete here).
 */
@RestController
@RequestMapping("/xms/clients")
@RequiredArgsConstructor
public class ClientController {

    private final ClientService clientService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER','GENERAL')")
    public ApiResponse<List<ClientResponse>> getActiveClients() {
        return ApiResponse.success(clientService.getActiveClients());
    }
}
