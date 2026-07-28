package com.expense_management_service.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Wires the AWS SDK v2 {@link S3Client} (upload/download/delete) and {@link S3Presigner}
 * (time-limited view/download URLs) beans used by {@code S3StorageService}.
 * <p>
 * Credentials come from {@link AwsProperties}, never from an implicit provider chain —
 * keeps XMS's AWS access explicit and consistent with how every other external
 * integration in this codebase is configured (see {@code RestClientConfig}).
 */
@Configuration
@RequiredArgsConstructor
public class S3Config {

    private final AwsProperties awsProperties;

    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(awsProperties.region()))
                .credentialsProvider(credentialsProvider())
                .build();
    }

    @Bean
    public S3Presigner s3Presigner() {
        return S3Presigner.builder()
                .region(Region.of(awsProperties.region()))
                .credentialsProvider(credentialsProvider())
                .build();
    }

    private StaticCredentialsProvider credentialsProvider() {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(awsProperties.accessKeyId(), awsProperties.secretAccessKey()));
    }
}
