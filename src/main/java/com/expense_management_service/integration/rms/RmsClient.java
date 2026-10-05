package com.expense_management_service.integration.rms;

import com.expense_management_service.integration.rms.dto.RmsClientResponse;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only client for client-master data that RMS owns.
 * <p>
 * Unlike {@code UmsClient}/{@code PmsClient}, calls made through this client use a dedicated
 * EMS service-account token (see {@code RmsServiceTokenProvider}), not the caller's own
 * forwarded JWT — RMS's client-lookup endpoints only accept {@code Admin}/{@code
 * Resource_Manager}/{@code Project_Manager} roles, which an ordinary {@code GENERAL} employee
 * never has.
 */
public interface RmsClient {

    /**
     * A single client by RMS's UUID. {@code GET /rms/api/client/{id}}.
     * Empty if RMS reports not-found (confirmed to return HTTP 400, not 404).
     */
    Optional<RmsClientResponse> getClient(UUID clientId);
}
