package com.ktb.chatapp.service;

import com.ktb.chatapp.dto.ProfileImageResponse;
import com.ktb.chatapp.dto.UpdateProfileRequest;
import com.ktb.chatapp.dto.UserResponse;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.exception.DirectUploadNotSupportedException;
import com.ktb.chatapp.storage.StorageKey;
import com.ktb.chatapp.storage.StoragePort;
import com.ktb.chatapp.util.FileUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final FileService fileService;
    private final StoragePort storagePort;
    private final MongoOperations mongoOperations;

    @Value("${app.profile.image.max-size:5242880}") // 5MB
    private long maxProfileImageSize;

    @Value("${app.s3.presign-ttl:10m}")
    private Duration profilePresignTtl;

    private static final List<String> ALLOWED_EXTENSIONS = Arrays.asList(
            "jpg", "jpeg", "png", "gif", "webp"
    );

    /**
     * 현재 사용자 프로필 조회
     * @param email 사용자 이메일
     */
    public UserResponse getCurrentUserProfile(String email) {
        User user = userRepository.findByEmail(email.toLowerCase())
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));
        return UserResponse.from(user);
    }

    /**
     * 사용자 프로필 업데이트
     * @param email 사용자 이메일
     */
    public UserResponse updateUserProfile(String email, UpdateProfileRequest request) {
        User updatedUser = mongoOperations.findAndModify(
                Query.query(Criteria.where("email").is(email.toLowerCase())),
                new Update()
                        .set("name", request.getName())
                        .set("updatedAt", LocalDateTime.now()),
                FindAndModifyOptions.options().returnNew(true),
                User.class);
        if (updatedUser == null) {
            throw new UsernameNotFoundException("사용자를 찾을 수 없습니다.");
        }
        log.debug("사용자 프로필 업데이트 완료 - ID: {}, Name: {}", updatedUser.getId(), request.getName());

        return UserResponse.from(updatedUser);
    }

    /**
     * 프로필 이미지 업로드 (보안 강화)
     * @param email 사용자 이메일
     */
    public ProfileImageResponse uploadProfileImage(String email, MultipartFile file) {
        // 파일 유효성 검증
        validateProfileImageFile(file);

        // 새 파일을 먼저 저장한다. 이후 DB 저장이 실패하면 새 파일을 정리해 기존 프로필을 보존한다.
        String profileImageKey = fileService.storeFile(file, "profiles");

        User previousUser;
        try {
            previousUser = mongoOperations.findAndModify(
                    Query.query(Criteria.where("email").is(email.toLowerCase())),
                    new Update()
                            .set("profileImage", profileImageKey)
                            .set("updatedAt", LocalDateTime.now()),
                    FindAndModifyOptions.options().returnNew(false),
                    User.class);
        } catch (RuntimeException e) {
            deleteProfileImageFile(profileImageKey, "DB 저장 실패 후 새 프로필 이미지 정리");
            throw e;
        }
        if (previousUser == null) {
            deleteProfileImageFile(profileImageKey, "사용자 없음으로 새 프로필 이미지 정리");
            throw new UsernameNotFoundException("사용자를 찾을 수 없습니다.");
        }

        // 새 key가 DB에 반영된 뒤에만 이전 실물 파일을 삭제한다.
        String oldProfileImageKey = previousUser.getProfileImage();
        if (oldProfileImageKey != null
                && !oldProfileImageKey.isEmpty()
                && !oldProfileImageKey.equals(profileImageKey)) {
            deleteProfileImageFile(oldProfileImageKey, "기존 프로필 이미지 삭제");
        }

        log.debug("프로필 이미지 업로드 완료 - User ID: {}, Key: {}", previousUser.getId(), profileImageKey);

        return ProfileImageResponse.updated(profileImageKey);
    }

    public PreparedProfileUpload prepareProfileImageUpload(
            String email,
            String originalFilename,
            String contentType,
            long size) {
        validateProfileImageMetadata(originalFilename, contentType, size);
        User user = userRepository.findByEmail(email.toLowerCase())
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));
        String safeFileName = user.getId() + "_" + FileUtil.generateSafeFileName(originalFilename);
        String key = StorageKey.profile(safeFileName);
        URI uploadUrl = storagePort.presignUploadUrl(key, contentType, profilePresignTtl)
                .orElseThrow(DirectUploadNotSupportedException::new);
        return new PreparedProfileUpload(uploadUrl.toString(), key);
    }

    public ProfileImageResponse completeProfileImageUpload(
            String email,
            String key,
            String originalFilename,
            String contentType,
            long size) {
        validateProfileImageMetadata(originalFilename, contentType, size);
        User user = userRepository.findByEmail(email.toLowerCase())
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));
        String expectedPrefix = StorageKey.profile(user.getId() + "_");
        if (key == null || !key.startsWith(expectedPrefix)) {
            throw new IllegalArgumentException("허용되지 않은 프로필 이미지 key입니다.");
        }

        var storedObject = storagePort.stat(key)
                .orElseThrow(() -> new IllegalArgumentException("업로드된 프로필 이미지를 찾을 수 없습니다."));
        if (storedObject.size() != size
                || (storedObject.contentType() != null && !storedObject.contentType().equals(contentType))) {
            throw new IllegalArgumentException("업로드된 프로필 이미지 정보가 일치하지 않습니다.");
        }

        User previousUser;
        try {
            previousUser = mongoOperations.findAndModify(
                    Query.query(Criteria.where("email").is(email.toLowerCase())),
                    new Update().set("profileImage", key).set("updatedAt", LocalDateTime.now()),
                    FindAndModifyOptions.options().returnNew(false),
                    User.class);
        } catch (RuntimeException e) {
            deleteProfileImageFile(key, "DB 저장 실패 후 직접 업로드 프로필 이미지 정리");
            throw e;
        }
        if (previousUser == null) {
            deleteProfileImageFile(key, "사용자 없음으로 직접 업로드 프로필 이미지 정리");
            throw new UsernameNotFoundException("사용자를 찾을 수 없습니다.");
        }
        String oldKey = previousUser.getProfileImage();
        if (oldKey != null && !oldKey.isEmpty() && !oldKey.equals(key)) {
            deleteProfileImageFile(oldKey, "기존 프로필 이미지 삭제");
        }
        return ProfileImageResponse.updated(key);
    }

    private void validateProfileImageMetadata(String originalFilename, String contentType, long size) {
        if (size <= 0 || size > maxProfileImageSize) {
            throw new IllegalArgumentException("파일 크기는 5MB를 초과할 수 없습니다.");
        }
        if (contentType == null || !contentType.startsWith("image/") || originalFilename == null) {
            throw new IllegalArgumentException("이미지 파일만 업로드할 수 있습니다.");
        }
        String extension = FileUtil.getFileExtension(originalFilename).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("이미지 파일만 업로드할 수 있습니다.");
        }
    }

    public record PreparedProfileUpload(String uploadUrl, String key) {
    }

    /**
     * 특정 사용자 프로필 조회
     */
    public UserResponse getUserProfile(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));

        return UserResponse.from(user);
    }

    /**
     * 프로필 이미지 파일 유효성 검증
     */
    private void validateProfileImageFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("이미지가 제공되지 않았습니다.");
        }

        // 파일 크기 검증
        if (file.getSize() > maxProfileImageSize) {
            throw new IllegalArgumentException("파일 크기는 5MB를 초과할 수 없습니다.");
        }

        // Content-Type 검증
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("이미지 파일만 업로드할 수 있습니다.");
        }

        // 파일 확장자 검증 (보안을 위해 화이트리스트 유지)
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null) {
            throw new IllegalArgumentException("이미지 파일만 업로드할 수 있습니다.");
        }

        // FileSecurityUtil의 static 메서드 호출
        String extension = FileUtil.getFileExtension(originalFilename).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("이미지 파일만 업로드할 수 있습니다.");
        }
    }

    /**
     * 기존 프로필 이미지 실물 삭제. 저장값이 key이므로 스토리지에 그대로 넘긴다 — 삭제 실패가 프로필 갱신
     * 자체를 막지는 않는다.
     */
    private void deleteProfileImageFile(String profileImageKey, String operation) {
        try {
            storagePort.delete(profileImageKey);
            log.debug("{} 완료: {}", operation, profileImageKey);
        } catch (RuntimeException e) {
            log.warn("{} 실패 - Key: {}, Cause: {}", operation, profileImageKey, e.getMessage());
        }
    }

    /**
     * 프로필 이미지 삭제
     * @param email 사용자 이메일
     */
    public void deleteProfileImage(String email) {
        Query emailQuery = Query.query(Criteria.where("email").is(email.toLowerCase()));
        Query imageQuery = Query.query(new Criteria().andOperator(
                Criteria.where("email").is(email.toLowerCase()),
                Criteria.where("profileImage").nin(null, "")));
        User previousUser = mongoOperations.findAndModify(
                imageQuery,
                new Update()
                        .set("profileImage", "")
                        .set("updatedAt", LocalDateTime.now()),
                FindAndModifyOptions.options().returnNew(false),
                User.class);

        if (previousUser == null) {
            if (!mongoOperations.exists(emailQuery, User.class)) {
                throw new UsernameNotFoundException("사용자를 찾을 수 없습니다.");
            }
            return;
        }

        deleteProfileImageFile(previousUser.getProfileImage(), "프로필 이미지 삭제");
        log.debug("프로필 이미지 삭제 완료 - User ID: {}", previousUser.getId());
    }

    /**
     * 회원 탈퇴 처리
     * @param email 사용자 이메일
     */
    public void deleteUserAccount(String email) {
        User user = userRepository.findByEmail(email.toLowerCase())
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));

        if (user.getProfileImage() != null && !user.getProfileImage().isEmpty()) {
            deleteProfileImageFile(user.getProfileImage(), "회원 탈퇴 프로필 이미지 삭제");
        }

        userRepository.delete(user);
        log.info("회원 탈퇴 완료 - User ID: {}", user.getId());
    }
}
