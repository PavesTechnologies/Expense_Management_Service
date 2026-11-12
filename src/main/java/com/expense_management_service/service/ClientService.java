package com.expense_management_service.service;

import com.expense_management_service.dto.response.ClientResponse;

import java.util.List;

/**
 * Read-only access to client-master data that RMS owns. XMS keeps no local copy of the list —
 * every call goes to RMS through {@code RmsClient}.
 */
public interface ClientService {

    /** Active clients from RMS, sorted by name. */
    List<ClientResponse> getActiveClients();
}
