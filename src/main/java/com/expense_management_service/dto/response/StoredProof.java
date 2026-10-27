package com.expense_management_service.dto.response;

import java.io.InputStream;

/** A payment proof file read back from storage. The caller must close {@code content}. */
public record StoredProof(String fileName, String contentType, InputStream content) {
}
