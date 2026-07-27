package com.expense_management_service.storage;

import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.Duration;

/**
 * Generic binary object storage abstraction — upload/download/delete a file addressed by
 * an opaque {@code objectKey}, plus time-limited pre-signed URLs for browser access.
 * <p>
 * Deliberately knows nothing about Receipts, expense reports, or any other XMS domain
 * concept: callers own the meaning of the key they pass in (e.g. {@code ReceiptServiceImpl}
 * builds a {@code receipts/{employeeId}/{reportId}/{lineItemId}/...} key), this interface
 * just moves bytes. Swapping the backing provider (S3 today) means implementing this
 * interface again, not touching any caller.
 */
public interface StorageService {

    /** Uploads {@code file} under {@code objectKey}. Never overwrites — callers must pass a unique key. */
    void upload(String objectKey, MultipartFile file);

    /** Streams the raw object bytes back — the caller is responsible for closing the stream. */
    InputStream download(String objectKey);

    /** Deletes the object. A missing object is not an error (idempotent). */
    void delete(String objectKey);

    /** Time-limited URL for inline browser preview (e.g. opening a PDF/image in a new tab). */
    String generateViewUrl(String objectKey, Duration ttl);

    /** Time-limited URL that forces a "Save As" download with the given file name. */
    String generateDownloadUrl(String objectKey, String downloadFileName, Duration ttl);
}
