package com.ktb.chatapp.service;

import com.ktb.chatapp.model.File;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.repository.FileRepository;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.storage.StoragePort;
import com.ktb.chatapp.exception.DeletedFileException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.stereotype.Service;

/**
 * 채팅 첨부 읽기 경로의 인가를 단독으로 소유한다. 스토리지 구현체는 권한을 모른다.
 *
 * <p>인가(파일→메시지→방→참가자 3홉)가 오프로딩 URL 발급보다 <b>항상</b> 먼저다. 그래서 오프로딩을 켜도
 * 비참가자에게 URL이 새지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileAccessService {

    /** 오프로딩 URL 수명. 발급 직후 곧바로 소비되는 흐름이므로 유출 창을 짧게 유지한다. */
    static final Duration OFFLOAD_URL_TTL = Duration.ofMinutes(5);

    private final StoragePort storagePort;
    private final FileRepository fileRepository;
    private final MessageRepository messageRepository;
    private final RoomRepository roomRepository;
    private final Optional<CloudFrontSignedUrlService>
            cloudFrontSignedUrlService;

    public FileAccess forDownload(String fileName, String requesterId) {
        return issue(authorize(fileName, requesterId), Delivery.ATTACHMENT, fileName, requesterId);
    }

    public FileAccess forView(
            String fileName,
            String requesterId
    ) {
        File fileEntity =
                authorize(fileName, requesterId);

        if (!fileEntity.isPreviewable()) {
            throw new PreviewNotSupportedException(
                    "미리보기를 지원하지 않는 파일 형식입니다."
            );
        }

        if (cloudFrontSignedUrlService.isPresent()) {
            URI signedUrl =
                    cloudFrontSignedUrlService
                            .get()
                            .sign(fileEntity.getPath());

            log.info(
                    "CloudFront signed URL 발급: {} (사용자: {})",
                    fileName,
                    requesterId
            );

            return new FileAccess.Redirect(signedUrl);
        }

        return issue(
                fileEntity,
                Delivery.INLINE,
                fileName,
                requesterId
        );
    }

    /**
     * 브라우저에 파일을 내보내는 두 방식. 오프로딩 URL에도 같은 방식을 실어야 다운로드 요청이 미리보기로
     * 바뀌지 않는다 — 스토리지가 직접 응답하면 컨트롤러가 헤더를 붙일 기회가 없다.
     */
    private enum Delivery {
        ATTACHMENT,
        INLINE;

        ContentDisposition of(String filename) {
            ContentDisposition.Builder builder =
                    this == ATTACHMENT ? ContentDisposition.attachment() : ContentDisposition.inline();
            return builder.filename(filename, StandardCharsets.UTF_8).build();
        }
    }

    private File authorize(String fileName, String requesterId) {
        // 1. 파일 조회
        File fileEntity = fileRepository.findByFilename(fileName)
                .orElseThrow(() -> new RuntimeException("파일을 찾을 수 없습니다: " + fileName));

        if (fileEntity.isDeleted()) {
            throw new DeletedFileException();
        }

        // 2. 메시지 조회 (파일과 메시지 연결 확인) - 효율적인 쿼리 메서드 사용
        Message message = messageRepository.findByFileId(fileEntity.getId())
                .orElseThrow(() -> new RuntimeException("파일과 연결된 메시지를 찾을 수 없습니다"));

        // 3. 방 조회 (사용자가 방 참가자인지 확인)
        Room room = roomRepository.findById(message.getRoomId())
                .orElseThrow(() -> new RuntimeException("방을 찾을 수 없습니다"));

        // 4. 권한 검증
        if (!room.getParticipantIds().contains(requesterId)) {
            log.warn("파일 접근 권한 없음: {} (사용자: {})", fileName, requesterId);
            throw new RuntimeException("파일에 접근할 권한이 없습니다");
        }

        return fileEntity;
    }

    private FileAccess issue(File fileEntity, Delivery delivery, String fileName, String requesterId) {
        Optional<URI> offloadUrl = storagePort.offloadUrl(
                fileEntity.getPath(), OFFLOAD_URL_TTL, delivery.of(fileEntity.getOriginalname()));
        if (offloadUrl.isPresent()) {
            log.info("파일 오프로딩 URL 발급: {} (사용자: {})", fileName, requesterId);
            return new FileAccess.Redirect(offloadUrl.get());
        }

        Resource resource = storagePort.open(fileEntity.getPath())
                .orElseThrow(() -> new RuntimeException("파일을 찾을 수 없습니다: " + fileName));
        log.info("파일 로드 성공: {} (사용자: {})", fileName, requesterId);
        return new FileAccess.Stream(
                resource,
                fileEntity.getOriginalname(),
                fileEntity.getMimetype(),
                fileEntity.getSize());
    }
}
