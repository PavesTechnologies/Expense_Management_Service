package com.expense_management_service.integration.rms;

import com.expense_management_service.integration.rms.dto.RmsActiveClientResponse;
import com.expense_management_service.integration.rms.dto.RmsClientResponse;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only client for client-master data that RMS owns.
 * <p>
 * Like {@code UmsClient}/{@code PmsClient}, calls carry the caller's own forwarded UMS JWT. RMS's
 * client-lookup endpoints only accept {@code Admin}/{@code Resource_Manager}/{@code
 * Project_Manager} roles, so for any other caller RMS responds 403, which propagates as a
 * {@code RestClientResponseException}.
 */
public interface RmsClient {

    /**
     * A single client by RMS's UUID. {@code GET /rms/api/client/{id}}.
     * Empty if RMS reports not-found (confirmed to return HTTP 400, not 404).
     */
    Optional<RmsClientResponse> getClient(UUID clientId);

    /**
     * All clients RMS reports as {@code ACTIVE}. {@code GET /rms/api/client/get-active-clients}
     * — the same endpoint the intranet frontend's project-creation modal uses. Empty if RMS
     * returns no {@code data}. Non-2xx responses propagate as {@code RestClientResponseException}.
     */
    List<RmsActiveClientResponse> getActiveClients();
}
