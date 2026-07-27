package com.expense_management_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code aws.s3.*} configuration namespace.
 *
 * @param bucketName name of the already-provisioned S3 bucket XMS stores receipt files in
 */
@ConfigurationProperties(prefix = "aws.s3")
public record S3Properties(String bucketName) {
}
