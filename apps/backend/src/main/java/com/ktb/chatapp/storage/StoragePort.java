package com.ktb.chatapp.storage;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;

public interface StoragePort {
    StoredObject put(InputStream content, String key, String contentType, long size);
    Optional<Resource> open(String key);
    void delete(String key);
    /**
     * 오프로딩 확장 지점. 지원하지 않으면 앱이 바이트를 중계한다.
     *
     * <p>{@code disposition}은 앱이 직접 서빙할 때 붙이는 것과 같은 헤더다. 오프로딩된 응답에도 실어야
     * 다운로드가 조용히 미리보기로 바뀌지 않는다.
     */
    default Optional<URI> offloadUrl(String key, Duration ttl, ContentDisposition disposition) {
        return Optional.empty();
    }

    default Optional<URI> presignUploadUrl(
            String key,
            String contentType,
            Duration ttl
    ) {
        return Optional.empty();
    }

    default Optional<StoredObjectMetadata> stat(String key) {
        return Optional.empty();
    }
}
