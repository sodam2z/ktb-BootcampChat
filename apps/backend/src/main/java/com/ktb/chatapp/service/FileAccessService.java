package com.ktb.chatapp.service;

import com.ktb.chatapp.model.File;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.exception.FileAccessException;
import com.ktb.chatapp.repository.FileRepository;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomAccessResult;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.storage.StoragePort;
import com.ktb.chatapp.exception.DeletedFileException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
public class FileAccessService {

    /** 오프로딩 URL 수명. 발급 직후 곧바로 소비되는 흐름이므로 유출 창을 짧게 유지한다. */
    static final Duration OFFLOAD_URL_TTL = Duration.ofMinutes(5);

    private final StoragePort storagePort;
    private final FileRepository fileRepository;
    private final MessageRepository messageRepository;
    private final RoomRepository roomRepository;
    private final Optional<CloudFrontSignedUrlService>
            cloudFrontSignedUrlService;
    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, Counter> accessCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> accessTimers = new ConcurrentHashMap<>();
    private final String instanceId;

    public FileAccessService(
            StoragePort storagePort,
            FileRepository fileRepository,
            MessageRepository messageRepository,
            RoomRepository roomRepository,
            Optional<CloudFrontSignedUrlService> cloudFrontSignedUrlService,
            MeterRegistry meterRegistry,
            @Value("${HOSTNAME:unknown}") String instanceId,
            @Value("${file.storage.type:local}") String storageType,
            @Value("${file.storage.require-offload:false}") boolean requireOffload) {
        if (requireOffload && !"s3".equalsIgnoreCase(storageType)) {
            throw new IllegalStateException(
                    "FILE_STORAGE_REQUIRE_OFFLOAD=true이면 FILE_STORAGE_TYPE=s3가 필요합니다.");
        }
        this.storagePort = storagePort;
        this.fileRepository = fileRepository;
        this.messageRepository = messageRepository;
        this.roomRepository = roomRepository;
        this.cloudFrontSignedUrlService = cloudFrontSignedUrlService;
        this.meterRegistry = meterRegistry;
        this.instanceId = instanceId;
        log.info(
                "파일 전달 구성: instanceId={}, storageType={}, cloudFront={}, requireOffload={}",
                instanceId,
                storageType,
                cloudFrontSignedUrlService.isPresent(),
                requireOffload);
    }

    public FileAccess forDownload(String fileName, String requesterId) {
        File fileEntity = authorize(fileName, requesterId, Operation.DOWNLOAD);
        return measureDelivery(
                Operation.DOWNLOAD,
                () -> issue(fileEntity, Delivery.ATTACHMENT, fileName, requesterId));
    }

    public FileAccess forView(
            String fileName,
            String requesterId
    ) {
        File fileEntity =
                authorize(fileName, requesterId, Operation.VIEW);
        return measureDelivery(Operation.VIEW, () -> {
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
        });
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

    private enum Operation {
        DOWNLOAD,
        VIEW
    }

    private File authorize(String fileName, String requesterId, Operation operation) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            File fileEntity = doAuthorize(fileName, requesterId, operation);
            stopAuthorizationTimer(sample, operation, "allowed", "participant");
            return fileEntity;
        } catch (FileAccessException exception) {
            stopAuthorizationTimer(sample, operation, "denied", exception.getReason().getCode());
            throw exception;
        } catch (DeletedFileException exception) {
            stopAuthorizationTimer(sample, operation, "denied", "deleted");
            throw exception;
        } catch (RuntimeException exception) {
            stopAuthorizationTimer(sample, operation, "error", "exception");
            throw exception;
        }
    }

    private File doAuthorize(String fileName, String requesterId, Operation operation) {
        // 1. 파일 조회
        File fileEntity = fileRepository.findByFilename(fileName)
                .orElseThrow(() -> failure(
                        operation,
                        FileAccessException.Reason.FILE_NOT_FOUND,
                        fileName,
                        null,
                        null,
                        null,
                        requesterId));

        if (fileEntity.isDeleted()) {
            record(operation, "denied", "deleted");
            throw new DeletedFileException();
        }

        // 2. 메시지 조회 (파일과 메시지 연결 확인) - 효율적인 쿼리 메서드 사용
        Message message = messageRepository.findRoomOnlyByFileId(fileEntity.getId())
                .orElseThrow(() -> failure(
                        operation,
                        FileAccessException.Reason.MESSAGE_NOT_FOUND,
                        fileName,
                        fileEntity.getId(),
                        null,
                        null,
                        requesterId));

        // 3. 방 존재 여부와 참가 여부를 작은 projection 한 번으로 판정한다.
        String roomId = message.getRoomId();
        Optional<RoomAccessResult> access = roomRepository.findAccessById(roomId, requesterId);
        if (access.isEmpty() || !access.get().participant()) {
            FileAccessException.Reason reason = access.isEmpty()
                    ? FileAccessException.Reason.ROOM_NOT_FOUND
                    : FileAccessException.Reason.NOT_PARTICIPANT;
            throw failure(
                    operation,
                    reason,
                    fileName,
                    fileEntity.getId(),
                    message.getId(),
                    roomId,
                    requesterId);
        }

        record(operation, "allowed", "participant");
        return fileEntity;
    }

    private FileAccess measureDelivery(Operation operation, Supplier<FileAccess> delivery) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            FileAccess access = delivery.get();
            String deliveryType = access instanceof FileAccess.Redirect ? "redirect" : "stream";
            stopDeliveryTimer(sample, operation, "success", deliveryType);
            return access;
        } catch (PreviewNotSupportedException exception) {
            stopDeliveryTimer(sample, operation, "denied", "unsupported");
            throw exception;
        } catch (RuntimeException exception) {
            stopDeliveryTimer(sample, operation, "error", "exception");
            throw exception;
        }
    }

    private FileAccessException failure(
            Operation operation,
            FileAccessException.Reason reason,
            String fileName,
            String fileId,
            String messageId,
            String roomId,
            String requesterId) {
        record(operation, "denied", reason.getCode());
        log.warn(
                "파일 접근 거부: instanceId={}, operation={}, reason={}, fileName={}, fileId={}, "
                        + "messageId={}, roomId={}, requesterId={}",
                instanceId != null ? instanceId : "unknown",
                operation.name().toLowerCase(),
                reason.getCode(),
                fileName,
                fileId,
                messageId,
                roomId,
                requesterId);
        return new FileAccessException(reason);
    }

    private void record(Operation operation, String outcome, String reason) {
        String operationName = operation.name().toLowerCase();
        String key = operationName + ':' + outcome + ':' + reason;
        accessCounters.computeIfAbsent(key, ignored ->
                        Counter.builder("file.access.requests")
                                .description("File read authorization outcomes")
                                .tag("operation", operationName)
                                .tag("outcome", outcome)
                                .tag("reason", reason)
                                .register(meterRegistry))
                .increment();
    }

    private void stopAuthorizationTimer(
            Timer.Sample sample, Operation operation, String outcome, String reason) {
        String operationName = operation.name().toLowerCase();
        String key = "authorization:" + operationName + ':' + outcome + ':' + reason;
        sample.stop(accessTimers.computeIfAbsent(key, ignored ->
                Timer.builder("file.access.authorization.duration")
                        .description("File authorization duration")
                        .tag("operation", operationName)
                        .tag("outcome", outcome)
                        .tag("reason", reason)
                        .register(meterRegistry)));
    }

    private void stopDeliveryTimer(
            Timer.Sample sample, Operation operation, String outcome, String delivery) {
        String operationName = operation.name().toLowerCase();
        String key = "delivery:" + operationName + ':' + outcome + ':' + delivery;
        sample.stop(accessTimers.computeIfAbsent(key, ignored ->
                Timer.builder("file.access.delivery.duration")
                        .description("File delivery preparation duration")
                        .tag("operation", operationName)
                        .tag("outcome", outcome)
                        .tag("delivery", delivery)
                        .register(meterRegistry)));
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
