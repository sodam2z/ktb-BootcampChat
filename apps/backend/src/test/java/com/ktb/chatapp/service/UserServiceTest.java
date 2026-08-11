package com.ktb.chatapp.service;

import com.ktb.chatapp.dto.ProfileImageResponse;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.storage.LocalStorage;
import com.ktb.chatapp.storage.StoragePort;
import com.ktb.chatapp.storage.StoredObjectMetadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService 단위 테스트")
class UserServiceTest {

    private static final String EMAIL = "user@example.com";

    @Mock
    private UserRepository userRepository;

    @Mock
    private FileService fileService;

    @Mock
    private MongoOperations mongoOperations;

    private UserService userService;

    @TempDir
    private Path uploadDir;

    /**
     * 실물 파일이 정말 지워지는지가 검증 대상이므로 스토리지는 목이 아니라 {@link LocalStorage} 실물을
     * @TempDir에 붙여 쓴다.
     */
    @BeforeEach
    void setUp() {
        userService = new UserService(
                userRepository, fileService, new LocalStorage(uploadDir.toString()), mongoOperations);
        ReflectionTestUtils.setField(userService, "maxProfileImageSize", 5242880L);
    }

    private Path createOldProfileImageFile(String fileName) throws IOException {
        Path profilesDir = uploadDir.resolve("profiles");
        Files.createDirectories(profilesDir);
        Path oldFile = profilesDir.resolve(fileName);
        Files.writeString(oldFile, "old-image-bytes");
        return oldFile;
    }

    @Test
    @DisplayName("프로필 이미지 재업로드 시 기존 이미지 실물 파일을 삭제한다")
    void uploadProfileImage_DeletesOldProfileImageFile() throws IOException {
        Path oldFile = createOldProfileImageFile("old.jpg");
        User user = User.builder()
                .id("user-1")
                .email(EMAIL)
                .profileImage("profiles/old.jpg")
                .build();
        when(fileService.storeFile(any(), eq("profiles"))).thenReturn("profiles/new.jpg");
        when(mongoOperations.findAndModify(
                any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(User.class)))
                .thenReturn(user);
        MockMultipartFile file = new MockMultipartFile(
                "file", "new.jpg", "image/jpeg", "new-image-bytes".getBytes());

        ProfileImageResponse response = userService.uploadProfileImage(EMAIL, file);

        assertThat(Files.exists(oldFile)).isFalse();
        assertThat(response.getImageUrl()).isEqualTo("/api/files/profiles/new.jpg");
    }

    @Test
    @DisplayName("프로필 이미지 DB 저장 실패 시 기존 파일을 보존하고 새 파일을 정리한다")
    void uploadProfileImage_WhenDatabaseSaveFails_PreservesOldFileAndDeletesNewFile() throws IOException {
        Path oldFile = createOldProfileImageFile("old-save-failure.jpg");
        Path newFile = createOldProfileImageFile("new-save-failure.jpg");
        User user = User.builder()
                .id("user-1")
                .email(EMAIL)
                .profileImage("profiles/old-save-failure.jpg")
                .build();
        when(fileService.storeFile(any(), eq("profiles")))
                .thenReturn("profiles/new-save-failure.jpg");
        when(mongoOperations.findAndModify(
                any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(User.class)))
                .thenThrow(new RuntimeException("mongo unavailable"));
        MockMultipartFile file = new MockMultipartFile(
                "file", "new.jpg", "image/jpeg", "new-image-bytes".getBytes());

        assertThatThrownBy(() -> userService.uploadProfileImage(EMAIL, file))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("mongo unavailable");

        assertThat(oldFile).exists();
        assertThat(newFile).doesNotExist();
    }

    @Test
    @DisplayName("프로필 직접 업로드는 파일 본문을 API로 받지 않고 presign과 metadata 확인만 수행한다")
    void directProfileUpload_UsesPresignAndMetadataOnly() {
        StoragePort storagePort = mock(StoragePort.class);
        UserService directService = new UserService(
                userRepository, fileService, storagePort, mongoOperations);
        ReflectionTestUtils.setField(directService, "maxProfileImageSize", 5242880L);
        ReflectionTestUtils.setField(directService, "profilePresignTtl", Duration.ofMinutes(10));
        User user = User.builder().id("user-1").email(EMAIL).build();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(storagePort.presignUploadUrl(any(), eq("image/jpeg"), eq(Duration.ofMinutes(10))))
                .thenReturn(Optional.of(URI.create("https://s3.example/upload")));

        UserService.PreparedProfileUpload prepared = directService.prepareProfileImageUpload(
                EMAIL, "profile.jpg", "image/jpeg", 1024L);

        assertThat(prepared.uploadUrl()).isEqualTo("https://s3.example/upload");
        assertThat(prepared.key()).startsWith("profiles/user-1_");

        when(storagePort.stat(prepared.key()))
                .thenReturn(Optional.of(new StoredObjectMetadata(1024L, "image/jpeg")));
        when(mongoOperations.findAndModify(
                any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(User.class)))
                .thenReturn(user);

        ProfileImageResponse response = directService.completeProfileImageUpload(
                EMAIL, prepared.key(), "profile.jpg", "image/jpeg", 1024L);

        assertThat(response.getImageUrl()).isEqualTo("/api/files/" + prepared.key());
    }

    @Test
    @DisplayName("프로필 이미지 삭제 시 기존 이미지 실물 파일을 삭제한다")
    void deleteProfileImage_DeletesProfileImageFile() throws IOException {
        Path oldFile = createOldProfileImageFile("old2.jpg");
        User user = User.builder()
                .id("user-1")
                .email(EMAIL)
                .profileImage("profiles/old2.jpg")
                .build();
        when(mongoOperations.findAndModify(
                any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(User.class)))
                .thenReturn(user);

        userService.deleteProfileImage(EMAIL);

        assertThat(Files.exists(oldFile)).isFalse();
    }

    @Test
    @DisplayName("프로필 이미지 DB 삭제 반영 실패 시 기존 파일을 보존한다")
    void deleteProfileImage_WhenDatabaseSaveFails_PreservesProfileImageFile() throws IOException {
        Path oldFile = createOldProfileImageFile("delete-save-failure.jpg");
        User user = User.builder()
                .id("user-1")
                .email(EMAIL)
                .profileImage("profiles/delete-save-failure.jpg")
                .build();
        when(mongoOperations.findAndModify(
                any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(User.class)))
                .thenThrow(new RuntimeException("mongo unavailable"));

        assertThatThrownBy(() -> userService.deleteProfileImage(EMAIL))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("mongo unavailable");

        assertThat(oldFile).exists();
    }
}
