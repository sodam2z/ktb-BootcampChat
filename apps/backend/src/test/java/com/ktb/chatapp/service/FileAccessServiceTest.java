package com.ktb.chatapp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ktb.chatapp.model.File;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.exception.FileAccessException;
import com.ktb.chatapp.repository.FileRepository;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomAccessResult;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.storage.StoragePort;
import com.ktb.chatapp.storage.StoredObject;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;

/**
 * 3홉 인가(파일→메시지→방→참가자)와 오프로딩 스위치의 상호작용을 고정한다.
 *
 * <p>핵심 계약: 비참가자는 스토리지가 오프로딩을 지원하든 말든 <b>URL 발급 전에</b> 거부된다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FileAccessService 단위 테스트")
class FileAccessServiceTest {

    private static final String FILE_NAME = "1700000000000_photo.png";
    private static final String KEY = "chat/" + FILE_NAME;
    private static final String FILE_ID = "file-id";
    private static final String ROOM_ID = "room-id";
    private static final String PARTICIPANT = "participant-id";
    private static final String OUTSIDER = "outsider-id";
    private static final String ORIGINAL_NAME = "여행 사진.png";
    private static final long SIZE = 4242L;
    private static final URI OFFLOADED_URL = URI.create("https://cdn.example.test/" + KEY + "?sig=stub");
    private static final Resource STORED_BYTES =
            new ByteArrayResource("photo-bytes".getBytes(StandardCharsets.UTF_8));

    @Mock
    private FileRepository fileRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private RoomRepository roomRepository;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    @DisplayName("참가자 + 오프로딩 지원 스토리지 → Redirect로 오프로딩된다")
    void forDownload_participantWithOffloadSupport_returnsRedirect() {
        OffloadingStorage storage = new OffloadingStorage();
        FileAccessService service = serviceWith(storage, "image/png");

        FileAccess access = service.forDownload(FILE_NAME, PARTICIPANT);

        assertThat(access).isInstanceOf(FileAccess.Redirect.class);
        assertThat(((FileAccess.Redirect) access).location()).isEqualTo(OFFLOADED_URL);
        assertThat(storage.offloadedKey).isEqualTo(KEY);
        assertThat(storage.openCalls).isZero();
        verify(messageRepository).findRoomOnlyByFileId(FILE_ID);
        verify(messageRepository, never()).findByFileId(FILE_ID);
        assertThat(meterRegistry.get("file.access.authorization.duration")
                .tags("operation", "download", "outcome", "allowed", "reason", "participant")
                .timer().count()).isEqualTo(1L);
        assertThat(meterRegistry.get("file.access.delivery.duration")
                .tags("operation", "download", "outcome", "success", "delivery", "redirect")
                .timer().count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("참가자 + 오프로딩 미지원 스토리지 → 앱이 중계하는 Stream을 조립한다")
    void forDownload_participantWithoutOffloadSupport_returnsStream() {
        DirectStorage storage = new DirectStorage();
        FileAccessService service = serviceWith(storage, "image/png");

        FileAccess access = service.forDownload(FILE_NAME, PARTICIPANT);

        assertThat(access).isInstanceOf(FileAccess.Stream.class);
        FileAccess.Stream stream = (FileAccess.Stream) access;
        assertThat(stream.resource()).isSameAs(STORED_BYTES);
        assertThat(stream.originalname()).isEqualTo(ORIGINAL_NAME);
        assertThat(stream.contentType()).isEqualTo("image/png");
        assertThat(stream.size()).isEqualTo(SIZE);
        assertThat(storage.openedKey).isEqualTo(KEY);
    }

    @Test
    @DisplayName("비참가자 + 오프로딩 지원 스토리지 → 오프로딩 호출 전에 거부된다")
    void forDownload_nonParticipantWithOffloadSupport_rejectsBeforeIssuingUrl() {
        OffloadingStorage storage = new OffloadingStorage();
        FileAccessService service = serviceWith(storage, "image/png");

        assertThatThrownBy(() -> service.forDownload(FILE_NAME, OUTSIDER))
                .isInstanceOfSatisfying(FileAccessException.class, exception ->
                        assertThat(exception.getReason())
                                .isEqualTo(FileAccessException.Reason.NOT_PARTICIPANT));

        assertThat(storage.offloadCalls).isZero();
        assertThat(storage.openCalls).isZero();
        assertThat(meterRegistry.get("file.access.requests")
                .tags("operation", "download", "outcome", "denied", "reason", "not_participant")
                .counter()
                .count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("비참가자 + 오프로딩 미지원 스토리지 → 같은 메시지로 거부된다")
    void forDownload_nonParticipantWithoutOffloadSupport_rejectsWithSameMessage() {
        DirectStorage storage = new DirectStorage();
        FileAccessService service = serviceWith(storage, "image/png");

        assertThatThrownBy(() -> service.forDownload(FILE_NAME, OUTSIDER))
                .isInstanceOfSatisfying(FileAccessException.class, exception ->
                        assertThat(exception.getReason())
                                .isEqualTo(FileAccessException.Reason.NOT_PARTICIPANT));

        assertThat(storage.openCalls).isZero();
    }

    @Test
    @DisplayName("오프로딩 URL TTL은 유한한 짧은 값으로 전달된다")
    void forDownload_passesBoundedOffloadTtl() {
        OffloadingStorage storage = new OffloadingStorage();
        FileAccessService service = serviceWith(storage, "image/png");

        service.forDownload(FILE_NAME, PARTICIPANT);

        assertThat(storage.offloadedTtl).isEqualTo(FileAccessService.OFFLOAD_URL_TTL);
        assertThat(FileAccessService.OFFLOAD_URL_TTL)
                .isGreaterThan(Duration.ZERO)
                .isLessThanOrEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("다운로드 오프로딩 URL에는 원본 파일명의 attachment 지시가 실린다")
    void forDownload_offloadUrlCarriesAttachmentDisposition() {
        OffloadingStorage storage = new OffloadingStorage();
        FileAccessService service = serviceWith(storage, "image/png");

        service.forDownload(FILE_NAME, PARTICIPANT);

        assertThat(storage.offloadedDisposition.isAttachment()).isTrue();
        assertThat(storage.offloadedDisposition.getFilename()).isEqualTo(ORIGINAL_NAME);
    }

    @Test
    @DisplayName("미리보기 오프로딩 URL은 inline 지시로 발급된다")
    void forView_offloadUrlCarriesInlineDisposition() {
        OffloadingStorage storage = new OffloadingStorage();
        FileAccessService service = serviceWith(storage, "image/png");

        service.forView(FILE_NAME, PARTICIPANT);

        assertThat(storage.offloadedDisposition.isInline()).isTrue();
        assertThat(storage.offloadedDisposition.getFilename()).isEqualTo(ORIGINAL_NAME);
    }

    @Test
    @DisplayName("미리보기 가능한 형식은 view에서도 Stream을 조립한다")
    void forView_previewableMimetype_returnsStream() {
        DirectStorage storage = new DirectStorage();
        FileAccessService service = serviceWith(storage, "image/png");

        assertThat(service.forView(FILE_NAME, PARTICIPANT)).isInstanceOf(FileAccess.Stream.class);
    }

    @Test
    @DisplayName("미리보기 미지원 형식은 view에서 PreviewNotSupportedException")
    void forView_nonPreviewableMimetype_throwsPreviewNotSupported() {
        DirectStorage storage = new DirectStorage();
        FileAccessService service = serviceWith(storage, "application/zip");

        assertThatThrownBy(() -> service.forView(FILE_NAME, PARTICIPANT))
                .isInstanceOf(PreviewNotSupportedException.class)
                .hasMessage("미리보기를 지원하지 않는 파일 형식입니다.");
    }

    @Test
    @DisplayName("비참가자에게는 미리보기 판정보다 인가 실패가 먼저다")
    void forView_nonParticipant_failsAuthorizationBeforePreviewCheck() {
        DirectStorage storage = new DirectStorage();
        FileAccessService service = serviceWith(storage, "application/zip");

        assertThatThrownBy(() -> service.forView(FILE_NAME, OUTSIDER))
                .isInstanceOfSatisfying(FileAccessException.class, exception ->
                        assertThat(exception.getReason())
                                .isEqualTo(FileAccessException.Reason.NOT_PARTICIPANT));
    }

    @Test
    @DisplayName("파일 메타데이터가 없으면 404 계약 메시지로 거부된다")
    void forDownload_missingFileEntity_throwsNotFound() {
        when(fileRepository.findByFilename(FILE_NAME)).thenReturn(Optional.empty());
        FileAccessService service = new FileAccessService(
                new DirectStorage(), fileRepository, messageRepository, roomRepository,
                Optional.empty(), meterRegistry, "test-instance", "local", false);

        assertThatThrownBy(() -> service.forDownload(FILE_NAME, PARTICIPANT))
                .isInstanceOfSatisfying(FileAccessException.class, exception ->
                        assertThat(exception.getReason())
                                .isEqualTo(FileAccessException.Reason.FILE_NOT_FOUND));
    }

    @Test
    @DisplayName("스토리지에 실물이 없으면 404 계약 메시지로 거부된다")
    void forDownload_missingStoredObject_throwsNotFound() {
        DirectStorage storage = new DirectStorage();
        storage.stored = false;
        FileAccessService service = serviceWith(storage, "image/png");

        assertThatThrownBy(() -> service.forDownload(FILE_NAME, PARTICIPANT))
                .hasMessage("파일을 찾을 수 없습니다: " + FILE_NAME);
    }

    @Test
    @DisplayName("오프로딩 필수 환경에서 local storage는 시작 전에 거부된다")
    void constructor_requiredOffloadWithLocalStorage_rejectsConfiguration() {
        assertThatThrownBy(() -> new FileAccessService(
                new DirectStorage(), fileRepository, messageRepository, roomRepository,
                Optional.empty(), meterRegistry, "test-instance", "local", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FILE_STORAGE_TYPE=s3");
    }

    private FileAccessService serviceWith(StoragePort storagePort, String mimetype) {
        when(fileRepository.findByFilename(FILE_NAME)).thenReturn(Optional.of(fileEntity(mimetype)));
        when(messageRepository.findRoomOnlyByFileId(FILE_ID)).thenReturn(Optional.of(
                Message.builder().id("message-id").roomId(ROOM_ID).fileId(FILE_ID).build()));
        lenient().when(roomRepository.findAccessById(ROOM_ID, PARTICIPANT))
                .thenReturn(Optional.of(new RoomAccessResult(true)));
        lenient().when(roomRepository.findAccessById(ROOM_ID, OUTSIDER))
                .thenReturn(Optional.of(new RoomAccessResult(false)));
        return new FileAccessService(
                storagePort, fileRepository, messageRepository, roomRepository,
                Optional.empty(), meterRegistry, "test-instance", "local", false);
    }

    private File fileEntity(String mimetype) {
        return File.builder()
                .id(FILE_ID)
                .filename(FILE_NAME)
                .originalname(ORIGINAL_NAME)
                .mimetype(mimetype)
                .size(SIZE)
                .path(KEY)
                .user("uploader-id")
                .build();
    }

    /** 오프로딩을 지원하지 않는 스토리지 — {@link StoragePort#offloadUrl}의 default를 그대로 쓴다. */
    private static final class DirectStorage implements StoragePort {

        private boolean stored = true;
        private String openedKey;
        private int openCalls;

        @Override
        public StoredObject put(InputStream content, String key, String contentType, long size) {
            throw new UnsupportedOperationException("읽기 경로 테스트에서는 쓰지 않는다");
        }

        @Override
        public Optional<Resource> open(String key) {
            openCalls++;
            openedKey = key;
            return stored ? Optional.of(STORED_BYTES) : Optional.empty();
        }

        @Override
        public void delete(String key) {
            throw new UnsupportedOperationException("읽기 경로 테스트에서는 쓰지 않는다");
        }
    }

    /** 오프로딩을 지원하는 스토리지 — 오프로딩 스위치가 켜진 상태를 모사한다. */
    private static final class OffloadingStorage implements StoragePort {

        private String offloadedKey;
        private Duration offloadedTtl;
        private ContentDisposition offloadedDisposition;
        private int offloadCalls;
        private int openCalls;

        @Override
        public StoredObject put(InputStream content, String key, String contentType, long size) {
            throw new UnsupportedOperationException("읽기 경로 테스트에서는 쓰지 않는다");
        }

        @Override
        public Optional<Resource> open(String key) {
            openCalls++;
            return Optional.of(STORED_BYTES);
        }

        @Override
        public void delete(String key) {
            throw new UnsupportedOperationException("읽기 경로 테스트에서는 쓰지 않는다");
        }

        @Override
        public Optional<URI> offloadUrl(String key, Duration ttl, ContentDisposition disposition) {
            offloadCalls++;
            offloadedKey = key;
            offloadedTtl = ttl;
            offloadedDisposition = disposition;
            return Optional.of(OFFLOADED_URL);
        }
    }
}
