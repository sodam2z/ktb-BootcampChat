package com.ktb.chatapp.service;

import com.ktb.chatapp.model.File;
import com.ktb.chatapp.repository.FileRepository;
import com.ktb.chatapp.storage.StorageKey;
import com.ktb.chatapp.storage.StoragePort;
import com.ktb.chatapp.util.FileUtil;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ChatFileDirectUploadService {

    private final StoragePort storagePort;
    private final FileRepository fileRepository;

    @Value("${app.s3.presign-ttl:10m}")
    private Duration presignTtl;

    public PreparedUpload prepare(
            String originalname,
            String mimetype,
            long size,
            String uploaderId
    ) {
        FileUtil.validateFileMetadata(
                originalname,
                mimetype,
                size
        );

        String normalizedOriginalname =
                FileUtil.normalizeOriginalFilename(originalname);

        String safeFileName =
                FileUtil.generateSafeFileName(normalizedOriginalname);

        String key =
                StorageKey.chat(uploaderId, safeFileName);

        URI uploadUrl = storagePort
                .presignUploadUrl(
                        key,
                        mimetype,
                        presignTtl
                )
                .orElseThrow(() ->
                        new IllegalStateException(
                                "현재 스토리지는 직접 업로드를 지원하지 않습니다."
                        )
                );

        return new PreparedUpload(
                uploadUrl.toString(),
                key,
                safeFileName
        );
    }

    public File complete(
            String key,
            String originalname,
            String mimetype,
            long size,
            String uploaderId
    ) {
        FileUtil.validateFileMetadata(
                originalname,
                mimetype,
                size
        );

        String safeFileName =
                FileUtil.getFileNameFromPath(key);

        String expectedKey =
                StorageKey.chat(
                        uploaderId,
                        safeFileName
                );

        if (!expectedKey.equals(key)) {
            throw new RuntimeException(
                    "허용되지 않은 업로드 key입니다."
            );
        }

        if (!FileUtil.isValidFilename(safeFileName)) {
            throw new RuntimeException(
                    "잘못된 파일명입니다."
            );
        }

        var storedObject = storagePort
                .stat(key)
                .orElseThrow(() ->
                        new RuntimeException(
                                "업로드된 파일을 찾을 수 없습니다."
                        )
                );

        if (storedObject.size() != size) {
            throw new RuntimeException(
                    "업로드된 파일 크기가 일치하지 않습니다."
            );
        }

        if (storedObject.contentType() != null
                && !storedObject.contentType().equals(mimetype)) {
            throw new RuntimeException(
                    "업로드된 파일 타입이 일치하지 않습니다."
            );
        }

        String normalizedOriginalname =
                FileUtil.normalizeOriginalFilename(originalname);

        File fileEntity = File.builder()
                .filename(safeFileName)
                .originalname(normalizedOriginalname)
                .mimetype(mimetype)
                .size(size)
                .path(key)
                .user(uploaderId)
                .uploadDate(LocalDateTime.now())
                .build();

        return fileRepository.save(fileEntity);
    }

    public record PreparedUpload(
            String uploadUrl,
            String key,
            String filename
    ) {
    }
}