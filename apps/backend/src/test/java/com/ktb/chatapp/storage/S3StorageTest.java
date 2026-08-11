package com.ktb.chatapp.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ContentDisposition;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@DisplayName("S3Storage 단위 테스트")
class S3StorageTest {

    private final S3Client s3Client = mock(S3Client.class);
    private final S3Presigner presigner = mock(S3Presigner.class);
    private final S3Storage storage = new S3Storage(
            s3Client, presigner, "profile-bucket", "loadtest/run-1", Duration.ofMinutes(10));

    @Test
    void profileUploadUsesPrefixContentTypeAndImmutableCache() {
        byte[] content = "image".getBytes();

        StoredObject result = storage.put(
                new ByteArrayInputStream(content), "profiles/avatar.jpg", "image/jpeg", content.length);

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().bucket()).isEqualTo("profile-bucket");
        assertThat(request.getValue().key()).isEqualTo("loadtest/run-1/profiles/avatar.jpg");
        assertThat(request.getValue().contentType()).isEqualTo("image/jpeg");
        assertThat(request.getValue().cacheControl()).isEqualTo(S3Storage.PROFILE_CACHE_CONTROL);
        assertThat(result.key()).isEqualTo("profiles/avatar.jpg");
    }

    @Test
    void chatUploadUsesBoundedPrivateCache() {
        byte[] content = "image".getBytes();

        storage.put(
                new ByteArrayInputStream(content),
                "chat/user-1/file.jpg",
                "image/jpeg",
                content.length);

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().cacheControl()).isEqualTo(S3Storage.CHAT_PREVIEW_CACHE_CONTROL);
    }

    @Test
    void deleteUsesSamePhysicalPrefix() {
        storage.delete("profiles/avatar.jpg");

        ArgumentCaptor<DeleteObjectRequest> request = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(request.capture());
        assertThat(request.getValue().key()).isEqualTo("loadtest/run-1/profiles/avatar.jpg");
    }

    @Test
    void offloadUrlReturnsPresignedGetUrl() throws Exception {
        PresignedGetObjectRequest signed = mock(PresignedGetObjectRequest.class);
        when(signed.url()).thenReturn(new URL("https://example.test/signed-profile"));
        when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(signed);

        URI result = storage.offloadUrl(
                        "profiles/avatar.jpg",
                        Duration.ofMinutes(10),
                        ContentDisposition.inline().filename("avatar.jpg").build())
                .orElseThrow();

        assertThat(result).isEqualTo(URI.create("https://example.test/signed-profile"));
        ArgumentCaptor<GetObjectPresignRequest> request =
                ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        verify(presigner).presignGetObject(request.capture());
        assertThat(request.getValue().getObjectRequest().responseCacheControl())
                .isEqualTo(S3Storage.CHAT_PREVIEW_CACHE_CONTROL);
    }

    @Test
    void directUploadSignsContentTypeAndBoundedPrivateCache() throws Exception {
        PresignedPutObjectRequest signed = mock(PresignedPutObjectRequest.class);
        when(signed.url()).thenReturn(new URL("https://example.test/signed-upload"));
        when(presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(signed);

        storage.presignUploadUrl("chat/user-1/file.jpg", "image/jpeg", Duration.ofMinutes(10));

        ArgumentCaptor<PutObjectPresignRequest> request =
                ArgumentCaptor.forClass(PutObjectPresignRequest.class);
        verify(presigner).presignPutObject(request.capture());
        assertThat(request.getValue().putObjectRequest().contentType()).isEqualTo("image/jpeg");
        assertThat(request.getValue().putObjectRequest().cacheControl())
                .isEqualTo(S3Storage.CHAT_PREVIEW_CACHE_CONTROL);
    }
}
