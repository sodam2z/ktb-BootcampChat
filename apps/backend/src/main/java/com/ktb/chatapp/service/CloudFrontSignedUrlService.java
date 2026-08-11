package com.ktb.chatapp.service;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import software.amazon.awssdk.services.cloudfront.CloudFrontUtilities;
import software.amazon.awssdk.services.cloudfront.model.CannedSignerRequest;

@Service
@ConditionalOnProperty(
        name = "app.cloudfront.enabled",
        havingValue = "true"
)
public class CloudFrontSignedUrlService {

    private final CloudFrontUtilities cloudFrontUtilities;
    private final String baseUrl;
    private final String keyPairId;
    private final Path privateKeyPath;
    private final Duration urlTtl;

    public CloudFrontSignedUrlService(
            @Value("${app.cloudfront.base-url}") String baseUrl,
            @Value("${app.cloudfront.key-pair-id}") String keyPairId,
            @Value("${app.cloudfront.private-key-path}") String privateKeyPath,
            @Value("${app.cloudfront.url-ttl:5m}") Duration urlTtl
    ) {
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalStateException(
                    "CLOUDFRONT_BASE_URL은 CloudFront 활성화 시 필수입니다."
            );
        }

        if (!StringUtils.hasText(keyPairId)) {
            throw new IllegalStateException(
                    "CLOUDFRONT_KEY_PAIR_ID는 CloudFront 활성화 시 필수입니다."
            );
        }

        if (!StringUtils.hasText(privateKeyPath)) {
            throw new IllegalStateException(
                    "CLOUDFRONT_PRIVATE_KEY_PATH는 CloudFront 활성화 시 필수입니다."
            );
        }

        Path keyPath = Path.of(privateKeyPath);

        if (!Files.isRegularFile(keyPath)) {
            throw new IllegalStateException(
                    "CloudFront private key 파일을 찾을 수 없습니다: "
                            + privateKeyPath
            );
        }

        this.cloudFrontUtilities = CloudFrontUtilities.create();
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.keyPairId = keyPairId;
        this.privateKeyPath = keyPath;
        this.urlTtl = urlTtl;
    }

    public URI sign(String objectKey) {
        String resourceUrl =
                baseUrl + "/" + normalizeObjectKey(objectKey);

        CannedSignerRequest request =
                CannedSignerRequest.builder()
                        .resourceUrl(resourceUrl)
                        .privateKey(privateKeyPath)
                        .keyPairId(keyPairId)
                        .expirationDate(
                                Instant.now().plus(urlTtl)
                        )
                        .build();

        String signedUrl =
                cloudFrontUtilities
                        .getSignedUrlWithCannedPolicy(request)
                        .url();

        return URI.create(signedUrl);
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String normalized = baseUrl.strip();

        while (normalized.endsWith("/")) {
            normalized =
                    normalized.substring(
                            0,
                            normalized.length() - 1
                    );
        }

        return normalized;
    }

    private static String normalizeObjectKey(String objectKey) {
        String normalized = objectKey;

        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        return normalized;
    }
}