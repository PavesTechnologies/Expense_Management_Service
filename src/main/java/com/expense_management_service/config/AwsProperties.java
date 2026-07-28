package com.expense_management_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code aws.*} configuration namespace (region + credentials).
 * <p>
 * Credentials are read explicitly from configuration (backed by env vars via
 * {@code spring-dotenv}) rather than relying on the AWS SDK's implicit default
 * credential provider chain, so XMS's AWS access is as explicit and testable as
 * every other external integration in this codebase (see {@link UmsProperties}).
 *
 * @param region          AWS region the S3 bucket lives in, e.g. {@code ap-south-1}
 * @param accessKeyId     AWS access key id — never logged or returned in any API response
 * @param secretAccessKey AWS secret access key — never logged or returned in any API response
 */
@ConfigurationProperties(prefix = "aws")
public record AwsProperties(String region, String accessKeyId, String secretAccessKey) {

    /** Overridden so credentials never leak through an accidental {@code log.debug(awsProperties)} or startup dump — records otherwise print every field verbatim. */
    @Override
    public String toString() {
        return "AwsProperties[region=" + region + ", accessKeyId=****, secretAccessKey=****]";
    }
}
