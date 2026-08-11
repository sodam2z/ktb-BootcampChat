package com.ktb.chatapp.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.util.StreamUtils;

@DisplayName("LocalStorage 단위 테스트")
class LocalStorageTest {

    @TempDir
    private java.nio.file.Path uploadDir;

    private LocalStorage localStorage;

    @BeforeEach
    void setUp() {
        localStorage = new LocalStorage(uploadDir.toString());
        localStorage.init();
    }

    private InputStream content(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("put()은 key 경로에 파일을 쓰고 StoredObject를 반환한다")
    void put_writesFileAndReturnsStoredObject() throws Exception {
        StoredObject result = localStorage.put(content("hello"), "profiles/avatar.png", "image/png", 5L);

        assertThat(result.key()).isEqualTo("profiles/avatar.png");
        assertThat(result.size()).isEqualTo(5L);
        assertThat(uploadDir.resolve("profiles/avatar.png")).exists();
    }

    @Test
    @DisplayName("open()은 저장된 key의 리소스를 반환한다")
    void open_returnsResourceForStoredKey() throws Exception {
        localStorage.put(content("hello"), "chat/photo.jpg", "image/jpeg", 5L);

        Optional<Resource> resource = localStorage.open("chat/photo.jpg");

        assertThat(resource).isPresent();
        try (InputStream inputStream = resource.get().getInputStream()) {
            assertThat(StreamUtils.copyToString(inputStream, StandardCharsets.UTF_8))
                    .isEqualTo("hello");
        }
    }

    @Test
    @DisplayName("open()은 존재하지 않는 key에 빈 Optional을 반환한다")
    void open_returnsEmptyForMissingKey() {
        assertThat(localStorage.open("chat/missing.jpg")).isEmpty();
    }

    @Test
    @DisplayName("delete()는 저장된 파일을 지운다")
    void delete_removesStoredFile() throws Exception {
        localStorage.put(content("hello"), "chat/to-delete.jpg", "image/jpeg", 5L);

        localStorage.delete("chat/to-delete.jpg");

        assertThat(uploadDir.resolve("chat/to-delete.jpg")).doesNotExist();
    }

    @Test
    @DisplayName("offloadUrl()의 default 구현은 빈 Optional이다(오프로딩 미지원)")
    void offloadUrl_defaultsToEmpty() {
        ContentDisposition attachment =
                ContentDisposition.attachment().filename("photo.jpg", StandardCharsets.UTF_8).build();

        assertThat(localStorage.offloadUrl("chat/photo.jpg", Duration.ofMinutes(5), attachment)).isEmpty();
    }

    @Test
    @DisplayName("경로 순회 key는 put에서 거부된다")
    void put_rejectsPathTraversalKey() {
        assertThatThrownBy(() -> localStorage.put(content("hello"), "../../etc/passwd", "text/plain", 5L))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("경로 순회 key는 open에서 거부된다")
    void open_rejectsPathTraversalKey() {
        assertThatThrownBy(() -> localStorage.open("../../etc/passwd"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("경로 순회 key는 delete에서 거부된다")
    void delete_rejectsPathTraversalKey() {
        assertThatThrownBy(() -> localStorage.delete("../../etc/passwd"))
                .isInstanceOf(RuntimeException.class);
    }
}
