package com.expense_management_service.integration.ums;

import com.expense_management_service.integration.ums.dto.EmployeeResponse;
import com.expense_management_service.integration.ums.dto.UmsUserResponse;
import com.expense_management_service.integration.ums.dto.UserProfileResponse;

import java.util.List;
import java.util.UUID;

/**
 * Read-only client for user/employee data that UMS owns.
 * <p>
 * XMS never mutates users, roles, or permissions through this client — it only
 * reads profile and employee data to display alongside expense records (e.g.
 * resolving a stored {@code created_by_uuid} to a display name). The bearer
 * token used for these calls is the caller's own token, forwarded automatically
 * by the {@code umsRestClient} interceptor — see {@code RestClientConfig}.
 */
public interface UmsClient {

    /** Fetches the profile of the currently authenticated caller. */
    UserProfileResponse getCurrentUser();

    /** Fetches a user's public profile by their UMS identity UUID ({@code obs_user_uuid}). */
    UserProfileResponse getUser(UUID uuid);

    /** Fetches employee information by UMS identity UUID. */
    EmployeeResponse getEmployee(UUID uuid);

    /** Fetches every user known to UMS — used to validate/resolve a numeric UMS {@code user_id} (e.g. a Cost Center owner). */
    List<UmsUserResponse> getAllUsers();
}
