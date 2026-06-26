// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.repo.s3;

/**
 * AWS credentials + region for signing S3 (and S3-compatible) requests. {@code sessionToken} is
 * null for long-lived keys, set for STS/temporary credentials (and then sent as {@code
 * x-amz-security-token}). {@code region} may be null when an explicit endpoint makes it irrelevant;
 * S3 signing still needs a region string, so callers default it (e.g. {@code us-east-1}).
 */
public record AwsCredentials(String accessKeyId, String secretAccessKey, String sessionToken, String region) {}
