package com.expense_management_service.storage;
import com.expense_management_service.config.S3Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;

/**
 * {@link StorageService} implementation backed by Amazon S3 (AWS SDK v2).
 * <p>
 * The bucket is never made public: every read goes through a time-limited pre-signed
 * URL ({@link #generateViewUrl}/{@link #generateDownloadUrl}) generated with
 * {@link S3Presigner}, which signs the request with XMS's own AWS credentials without
 * ever exposing them to the client.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class S3StorageService implements StorageService {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final S3Properties s3Properties;

    @Override
    public void upload(String objectKey, MultipartFile file) {
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(s3Properties.bucketName())
                    .key(objectKey)
                    .contentType(file.getContentType())
                    .contentLength(file.getSize())
                    .build();
            s3Client.putObject(request, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        } catch (IOException e) {
            throw new StorageException("Could not read the uploaded file", e);
        } catch (SdkException e) {
            // Deliberately excludes the object key from the message — this exception's
            // message ends up in application logs, and object keys are never logged (see
            // ReceiptServiceImpl). The key is still recoverable from the Receipt row itself.
            throw new StorageException("Failed to upload file to storage", e);
        }
    }

    @Override
    public InputStream download(String objectKey) {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(s3Properties.bucketName())
                    .key(objectKey)
                    .build();
            return s3Client.getObject(request);
        } catch (NoSuchKeyException e) {
            throw new StorageException("File not found in storage", e);
        } catch (SdkException e) {
            throw new StorageException("Failed to download file from storage", e);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(s3Properties.bucketName())
                    .key(objectKey)
                    .build());
        } catch (SdkException e) {
            throw new StorageException("Failed to delete file from storage", e);
        }
    }

    @Override
    public String generateViewUrl(String objectKey, Duration ttl) {
        return presign(objectKey, ttl, "inline");
    }

    @Override
    public String generateDownloadUrl(String objectKey, String downloadFileName, Duration ttl) {
        String sanitizedFileName = downloadFileName.replaceAll("[\\r\\n\"]", "_");
        return presign(objectKey, ttl, "attachment; filename=\"" + sanitizedFileName + "\"");
    }

    private String presign(String objectKey, Duration ttl, String contentDisposition) {
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(s3Properties.bucketName())
                    .key(objectKey)
                    .responseContentDisposition(contentDisposition)
                    .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(ttl)
                    .getObjectRequest(getObjectRequest)
                    .build();

            return s3Presigner.presignGetObject(presignRequest).url().toString();
        } catch (SdkException e) {
            throw new StorageException("Failed to generate a pre-signed URL", e);
        }
    }
}
