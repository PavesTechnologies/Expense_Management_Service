package com.expense_management_service.storage;

import com.expense_management_service.config.S3Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3StorageServiceTest {

    @Mock
    private S3Client s3Client;
    @Mock
    private S3Presigner s3Presigner;

    private S3StorageService storageService;

    @BeforeEach
    void setUp() {
        storageService = new S3StorageService(s3Client, s3Presigner, new S3Properties("expense-management-files"));
    }

    @Test
    void upload_putsObjectWithGivenKeyAndContentType() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "receipt.pdf", "application/pdf", "content".getBytes());

        storageService.upload("receipts/emp1/report1/line1/uuid-receipt.pdf", file);

        verify(s3Client).putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class));
    }

    @Test
    void upload_throwsStorageException_whenS3Fails() {
        MockMultipartFile file = new MockMultipartFile("file", "receipt.pdf", "application/pdf", "content".getBytes());
        when(s3Client.putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class)))
                .thenThrow(SdkException.create("boom", null));

        assertThatThrownBy(() -> storageService.upload("key", file)).isInstanceOf(StorageException.class);
    }

    @Test
    void download_returnsObjectStream() {
        InputStream stubStream = mock(InputStream.class);
        software.amazon.awssdk.core.ResponseInputStream<software.amazon.awssdk.services.s3.model.GetObjectResponse> responseStream =
                mock(software.amazon.awssdk.core.ResponseInputStream.class);
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(responseStream);

        InputStream result = storageService.download("key");

        assertThat(result).isSameAs(responseStream);
    }

    @Test
    void download_throwsStorageException_whenKeyMissing() {
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("no such key").build());

        assertThatThrownBy(() -> storageService.download("missing-key")).isInstanceOf(StorageException.class);
    }

    @Test
    void delete_removesObject() {
        storageService.delete("key");

        verify(s3Client).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void delete_throwsStorageException_whenS3Fails() {
        when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(SdkException.create("boom", null));

        assertThatThrownBy(() -> storageService.delete("key")).isInstanceOf(StorageException.class);
    }

    @Test
    void generateViewUrl_returnsInlinePresignedUrl() throws MalformedURLException {
        PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(new URL("https://s3.example.com/key?signed=1"));
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

        String url = storageService.generateViewUrl("key", Duration.ofMinutes(15));

        assertThat(url).isEqualTo("https://s3.example.com/key?signed=1");
    }

    @Test
    void generateDownloadUrl_sanitizesFileNameInContentDisposition() throws MalformedURLException {
        PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(new URL("https://s3.example.com/key?signed=1"));
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

        String url = storageService.generateDownloadUrl("key", "receipt\r\n\"evil\".pdf", Duration.ofMinutes(15));

        assertThat(url).isEqualTo("https://s3.example.com/key?signed=1");
    }

    @Test
    void generateViewUrl_throwsStorageException_whenPresignFails() {
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenThrow(SdkException.create("boom", null));

        assertThatThrownBy(() -> storageService.generateViewUrl("key", Duration.ofMinutes(15)))
                .isInstanceOf(StorageException.class);
    }
}
