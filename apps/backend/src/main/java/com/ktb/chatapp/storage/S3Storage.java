package com.ktb.chatapp.storage;

import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.ContentDisposition;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

@Component
@ConditionalOnProperty(name = "file.storage.type", havingValue = "s3")
public class S3Storage implements StoragePort {

    static final String PROFILE_CACHE_CONTROL = "public, max-age=31536000, immutable";
    static final String PRIVATE_CACHE_CONTROL = "private, no-cache, no-store, must-revalidate";

    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final String bucket;
    private final String keyPrefix;
    private final Duration fallbackPresignTtl;

    public S3Storage(
            S3Client s3Client,
            S3Presigner presigner,
            @Value("${app.s3.bucket}") String bucket,
            @Value("${app.s3.key-prefix:}") String keyPrefix,
            @Value("${app.s3.presign-ttl:10m}") Duration fallbackPresignTtl) {
        if (!StringUtils.hasText(bucket)) {
            throw new IllegalStateException("S3_BUCKET은 file.storage.type=s3일 때 필수입니다.");
        }
        this.s3Client = s3Client;
        this.presigner = presigner;
        this.bucket = bucket;
        this.keyPrefix = normalizePrefix(keyPrefix);
        this.fallbackPresignTtl = fallbackPresignTtl;
    }

    @Override
    public StoredObject put(InputStream content, String key, String contentType, long size) {
        String objectKey = objectKey(key);
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentType(contentType)
                .contentLength(size)
                .cacheControl(StorageKey.isProfile(key) ? PROFILE_CACHE_CONTROL : PRIVATE_CACHE_CONTROL)
                .build();
        s3Client.putObject(request, RequestBody.fromInputStream(content, size));
        return new StoredObject(key, size);
    }

    @Override
    public Optional<Resource> open(String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey(key)).build());
            URI location = offloadUrl(key, fallbackPresignTtl, ContentDisposition.inline().build())
                    .orElseThrow();
            return Optional.of(new UrlResource(location));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (MalformedURLException e) {
            throw new IllegalStateException("S3 presigned URL을 Resource로 변환할 수 없습니다.", e);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public void delete(String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey(key))
                .build());
    }

    @Override
    public Optional<URI> offloadUrl(String key, Duration ttl, ContentDisposition disposition) {
        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey(key))
                .responseContentDisposition(disposition.toString())
                .build();
        return Optional.of(presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(ttl)
                        .getObjectRequest(getRequest)
                        .build())
                .url()
                .toExternalForm())
                .map(URI::create);
    }

    private String objectKey(String key) {
        return keyPrefix + key;
    }

    private static String normalizePrefix(String prefix) {
        if (!StringUtils.hasText(prefix)) {
            return "";
        }
        String normalized = prefix.strip();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }
}
