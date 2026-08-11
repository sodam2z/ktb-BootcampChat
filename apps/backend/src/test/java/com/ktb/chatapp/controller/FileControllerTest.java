package com.ktb.chatapp.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ktb.chatapp.model.User;
import com.ktb.chatapp.exception.DirectUploadNotSupportedException;
import com.ktb.chatapp.exception.FileAccessException;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.security.SessionAwareJwtAuthenticationConverter;
import com.ktb.chatapp.service.FileAccess;
import com.ktb.chatapp.service.FileAccessService;
import com.ktb.chatapp.service.ChatFileDirectUploadService;
import com.ktb.chatapp.service.FileService;
import com.ktb.chatapp.service.PreviewNotSupportedException;
import com.ktb.chatapp.service.RateLimitService;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code /api/files} 읽기 경로의 HTTP 표면을 고정한다. {@link FileAccessService}가 조립한
 * {@link FileAccess}를 컨트롤러가 어떤 상태코드·헤더로 번역하는지가 검증 대상이다.
 *
 * <p>파일 읽기 예외의 타입별 상태 코드와 download/view의 서로 다른 응답 헤더를 검증한다.
 */
@WebMvcTest(controllers = FileController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("FileController 표면 계약")
class FileControllerTest {

    private static final String EMAIL = "user@example.com";
    private static final String USER_ID = "user-1";
    private static final String FILE_NAME = "1700000000000_abcdef0123456789.png";
    private static final String ORIGINAL_NAME = "여행 사진.png";
    private static final String ENCODED_ORIGINAL_NAME = "%EC%97%AC%ED%96%89%20%EC%82%AC%EC%A7%84.png";
    private static final Resource STORED_BYTES =
            new ByteArrayResource("photo-bytes".getBytes(StandardCharsets.UTF_8));
    private static final Principal PRINCIPAL = () -> EMAIL;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FileService fileService;

    @MockitoBean
    private FileAccessService fileAccessService;

    @MockitoBean
    private ChatFileDirectUploadService chatFileDirectUploadService;

    @MockitoBean
    private UserRepository userRepository;

    /** RateLimitInterceptor가 웹 슬라이스에 함께 올라오므로 그 의존성도 채워야 한다. */
    @MockitoBean
    private RateLimitService rateLimitService;

    /** Converter 구현체도 웹 슬라이스에 포함된다 — SessionService까지 끌어오지 않도록 목으로 끊는다. */
    @MockitoBean
    private SessionAwareJwtAuthenticationConverter jwtAuthenticationConverter;

    @BeforeEach
    void setUp() {
        when(userRepository.findByEmail(EMAIL))
                .thenReturn(Optional.of(User.builder().id(USER_ID).email(EMAIL).build()));
    }

    @Test
    @DisplayName("download + Stream → attachment 헤더와 캐시 금지 헤더")
    void downloadFile_stream_setsAttachmentAndNoCacheHeaders() throws Exception {
        when(fileAccessService.forDownload(FILE_NAME, USER_ID)).thenReturn(stream());

        mockMvc.perform(get("/api/files/download/{filename}", FILE_NAME).principal(PRINCIPAL))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().longValue("Content-Length", 11L))
                .andExpect(header().string(
                        "Content-Disposition",
                        "attachment; filename*=UTF-8''" + ENCODED_ORIGINAL_NAME))
                .andExpect(header().string(
                        "Cache-Control", "private, no-cache, no-store, must-revalidate"))
                .andExpect(header().string(
                        "Access-Control-Expose-Headers", "Content-Disposition"));
    }

    @Test
    @DisplayName("view + Stream → inline 헤더")
    void viewFile_stream_setsInlineHeader() throws Exception {
        when(fileAccessService.forView(FILE_NAME, USER_ID)).thenReturn(stream());

        mockMvc.perform(get("/api/files/view/{filename}", FILE_NAME).principal(PRINCIPAL))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string(
                        "Content-Disposition",
                        "inline; filename=\"" + ORIGINAL_NAME + "\"; filename*=UTF-8''"
                                + ENCODED_ORIGINAL_NAME))
                .andExpect(header().string("Cache-Control", "private, max-age=300"));
    }

    @Test
    @DisplayName("인증 details의 userId를 사용해 사용자 재조회를 생략한다")
    void viewFile_authenticatedUserId_skipsUserLookup() throws Exception {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(EMAIL, "", List.of());
        authentication.setDetails(Map.of("userId", USER_ID));
        when(fileAccessService.forView(FILE_NAME, USER_ID)).thenReturn(stream());

        mockMvc.perform(get("/api/files/view/{filename}", FILE_NAME).principal(authentication))
                .andExpect(status().isOk());

        verify(userRepository, never()).findByEmail(EMAIL);
    }

    @Test
    @DisplayName("download + Redirect → 302와 Location (오프로딩)")
    void downloadFile_redirect_returnsFoundWithLocation() throws Exception {
        when(fileAccessService.forDownload(FILE_NAME, USER_ID)).thenReturn(redirect());

        mockMvc.perform(get("/api/files/download/{filename}", FILE_NAME).principal(PRINCIPAL))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://cdn.example.test/chat/" + FILE_NAME + "?sig=stub"));
    }

    @Test
    @DisplayName("view + Redirect → 302와 Location (오프로딩)")
    void viewFile_redirect_returnsFoundWithLocation() throws Exception {
        when(fileAccessService.forView(FILE_NAME, USER_ID)).thenReturn(redirect());

        mockMvc.perform(get("/api/files/view/{filename}", FILE_NAME).principal(PRINCIPAL))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://cdn.example.test/chat/" + FILE_NAME + "?sig=stub"));
    }

    @Test
    @DisplayName("비참가자 예외 → 403")
    void downloadFile_nonParticipant_returnsForbidden() throws Exception {
        when(fileAccessService.forDownload(FILE_NAME, USER_ID))
                .thenThrow(new FileAccessException(FileAccessException.Reason.NOT_PARTICIPANT));

        mockMvc.perform(get("/api/files/download/{filename}", FILE_NAME).principal(PRINCIPAL))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("파일에 접근할 권한이 없습니다."));
    }

    @Test
    @DisplayName("파일 미발견 예외 → 404")
    void downloadFile_fileNotFound_returnsNotFound() throws Exception {
        when(fileAccessService.forDownload(FILE_NAME, USER_ID))
                .thenThrow(new FileAccessException(FileAccessException.Reason.FILE_NOT_FOUND));

        mockMvc.perform(get("/api/files/download/{filename}", FILE_NAME).principal(PRINCIPAL))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("파일을 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("view의 비참가자 예외도 같은 403 계약을 따른다")
    void viewFile_nonParticipant_returnsForbidden() throws Exception {
        when(fileAccessService.forView(FILE_NAME, USER_ID))
                .thenThrow(new FileAccessException(FileAccessException.Reason.NOT_PARTICIPANT));

        mockMvc.perform(get("/api/files/view/{filename}", FILE_NAME).principal(PRINCIPAL))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("파일에 접근할 권한이 없습니다."));
    }

    @Test
    @DisplayName("미리보기 미지원 → 415와 예외 메시지 그대로")
    void viewFile_previewNotSupported_returnsUnsupportedMediaType() throws Exception {
        when(fileAccessService.forView(FILE_NAME, USER_ID))
                .thenThrow(new PreviewNotSupportedException("미리보기를 지원하지 않는 파일 형식입니다."));

        mockMvc.perform(get("/api/files/view/{filename}", FILE_NAME).principal(PRINCIPAL))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("미리보기를 지원하지 않는 파일 형식입니다."));
    }

    @Test
    @DisplayName("업로더가 아닌 사용자의 삭제 → 403")
    void deleteFile_unauthorizedMessage_returnsForbidden() throws Exception {
        when(fileService.deleteFile(any(), any()))
                .thenThrow(new RuntimeException("파일을 삭제할 권한이 없습니다."));

        mockMvc.perform(delete("/api/files/{id}", "file-1").principal(PRINCIPAL))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("파일을 삭제할 권한이 없습니다."));
    }

    @Test
    @DisplayName("presign 신규·호환 경로가 같은 응답을 반환한다")
    void presignAliases_returnSameUploadContract() throws Exception {
        when(chatFileDirectUploadService.prepare(
                eq("profile.jpg"), eq("image/jpeg"), eq(1024L), eq(USER_ID)))
                .thenReturn(new ChatFileDirectUploadService.PreparedUpload(
                        "https://s3.example.test/signed-upload",
                        "chat/user-1/safe.jpg",
                        "safe.jpg"));

        String requestBody = """
                {"originalname":"profile.jpg","mimetype":"image/jpeg","size":1024}
                """;

        for (String path : new String[]{"/api/files/presign", "/api/files/upload/presign"}) {
            mockMvc.perform(post(path)
                            .principal(PRINCIPAL)
                            .contentType("application/json")
                            .content(requestBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.uploadUrl").value("https://s3.example.test/signed-upload"))
                    .andExpect(jsonPath("$.key").value("chat/user-1/safe.jpg"));
        }
    }

    @Test
    @DisplayName("직접 업로드 미지원 스토리지는 409로 폴백 가능 여부를 알린다")
    void presign_notSupported_returnsConflict() throws Exception {
        when(chatFileDirectUploadService.prepare(
                eq("profile.jpg"), eq("image/jpeg"), eq(1024L), eq(USER_ID)))
                .thenThrow(new DirectUploadNotSupportedException());

        mockMvc.perform(post("/api/files/presign")
                        .principal(PRINCIPAL)
                        .contentType("application/json")
                        .content("""
                                {"originalname":"profile.jpg","mimetype":"image/jpeg","size":1024}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value("현재 스토리지는 직접 업로드를 지원하지 않습니다."));
    }

    private FileAccess stream() {
        return new FileAccess.Stream(STORED_BYTES, ORIGINAL_NAME, "image/png", 11L);
    }

    private FileAccess redirect() {
        return new FileAccess.Redirect(
                URI.create("https://cdn.example.test/chat/" + FILE_NAME + "?sig=stub"));
    }
}
