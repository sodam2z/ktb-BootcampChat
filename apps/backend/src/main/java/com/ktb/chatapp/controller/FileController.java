package com.ktb.chatapp.controller;

import com.ktb.chatapp.dto.StandardResponse;
import com.ktb.chatapp.exception.DeletedFileException;
import com.ktb.chatapp.model.File;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.ChatFileDirectUploadService;
import com.ktb.chatapp.service.FileAccess;
import com.ktb.chatapp.service.FileAccessService;
import com.ktb.chatapp.service.FileService;
import com.ktb.chatapp.service.FileUploadResult;
import com.ktb.chatapp.service.PreviewNotSupportedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(
        name = "파일 (Files)",
        description = "파일 업로드 및 다운로드 API"
)
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/files")
public class FileController {

    private final FileService fileService;
    private final FileAccessService fileAccessService;
    private final UserRepository userRepository;
    private final ChatFileDirectUploadService
            chatFileDirectUploadService;

    /**
     * 기존 Multipart 업로드.
     *
     * 기존 기능 보존용으로 남겨둔다.
     */
    @Operation(
            summary = "파일 업로드",
            description = "파일을 업로드합니다. 최대 50MB까지 가능합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "파일 업로드 성공"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "잘못된 파일",
                    content = @Content(
                            schema = @Schema(
                                    implementation =
                                            StandardResponse.class
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "인증 실패",
                    content = @Content(
                            schema = @Schema(
                                    implementation =
                                            StandardResponse.class
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "413",
                    description = "파일 크기 초과",
                    content = @Content(
                            schema = @Schema(
                                    implementation =
                                            StandardResponse.class
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "서버 내부 오류",
                    content = @Content(
                            schema = @Schema(
                                    implementation =
                                            StandardResponse.class
                            )
                    )
            )
    })
    @PostMapping("/upload")
    public ResponseEntity<?> uploadFile(
            @Parameter(description = "업로드할 파일")
            @RequestParam("file")
            MultipartFile file,
            Principal principal
    ) {
        try {
            User user = getUser(principal);

            FileUploadResult result =
                    fileService.uploadFile(
                            file,
                            user.getId()
                    );

            if (!result.isSuccess()) {
                return ResponseEntity
                        .status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(
                                Map.of(
                                        "success",
                                        false,
                                        "message",
                                        "파일 업로드에 실패했습니다."
                                )
                        );
            }

            Map<String, Object> response =
                    new HashMap<>();

            response.put("success", true);
            response.put(
                    "message",
                    "파일 업로드 성공"
            );

            response.put(
                    "file",
                    toFileResponse(result.getFile())
            );

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error(
                    "파일 업로드 중 에러 발생",
                    e
            );

            Map<String, Object> errorResponse =
                    new HashMap<>();

            errorResponse.put(
                    "success",
                    false
            );

            errorResponse.put(
                    "message",
                    "파일 업로드 중 오류가 발생했습니다."
            );

            errorResponse.put(
                    "error",
                    e.getMessage()
            );

            return ResponseEntity
                    .status(
                            HttpStatus.INTERNAL_SERVER_ERROR
                    )
                    .body(errorResponse);
        }
    }

    /**
     * S3 직접 업로드를 위한 presigned PUT URL 발급.
     *
     * 파일 본문은 Backend로 들어오지 않는다.
     */
    @PostMapping({"/presign", "/upload/presign"})
    public ResponseEntity<?> createUploadUrl(
            @RequestBody
            DirectUploadPresignRequest request,
            Principal principal
    ) {
        try {
            User user = getUser(principal);

            ChatFileDirectUploadService.PreparedUpload
                    prepared =
                    chatFileDirectUploadService.prepare(
                            request.originalname(),
                            request.mimetype(),
                            request.size(),
                            user.getId()
                    );

            Map<String, Object> response =
                    new HashMap<>();

            response.put("success", true);
            response.put(
                    "uploadUrl",
                    prepared.uploadUrl()
            );
            response.put(
                    "key",
                    prepared.key()
            );
            response.put(
                    "filename",
                    prepared.filename()
            );

            return ResponseEntity.ok(response);

        } catch (RuntimeException e) {
            log.warn(
                    "파일 presign 발급 실패",
                    e
            );

            return ResponseEntity
                    .badRequest()
                    .body(
                            Map.of(
                                    "success",
                                    false,
                                    "message",
                                    safeMessage(e)
                            )
                    );
        }
    }

    /**
     * 브라우저가 S3 업로드 완료 후 호출.
     *
     * Backend는 실제 S3 객체를 확인한 후
     * MongoDB metadata만 저장한다.
     */
    @PostMapping("/upload/complete")
    public ResponseEntity<?> completeUpload(
            @RequestBody
            DirectUploadCompleteRequest request,
            Principal principal
    ) {
        try {
            User user = getUser(principal);

            File savedFile =
                    chatFileDirectUploadService.complete(
                            request.key(),
                            request.originalname(),
                            request.mimetype(),
                            request.size(),
                            user.getId()
                    );

            Map<String, Object> response =
                    new HashMap<>();

            response.put("success", true);
            response.put(
                    "file",
                    toFileResponse(savedFile)
            );

            return ResponseEntity.ok(response);

        } catch (RuntimeException e) {
            log.warn(
                    "S3 직접 업로드 완료 처리 실패",
                    e
            );

            return ResponseEntity
                    .badRequest()
                    .body(
                            Map.of(
                                    "success",
                                    false,
                                    "message",
                                    safeMessage(e)
                            )
                    );
        }
    }

    /**
     * 파일 다운로드.
     */
    @Operation(
            summary = "파일 다운로드",
            description =
                    "업로드된 파일을 다운로드합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "파일 다운로드 성공"
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "인증 실패",
                    content = @Content(
                            schema = @Schema(
                                    implementation =
                                            StandardResponse.class
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "권한 없음",
                    content = @Content(
                            schema = @Schema(
                                    implementation =
                                            StandardResponse.class
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "파일을 찾을 수 없음",
                    content = @Content(
                            schema = @Schema(
                                    implementation =
                                            StandardResponse.class
                            )
                    )
            )
    })
    @GetMapping("/download/{filename:.+}")
    public ResponseEntity<?> downloadFile(
            @Parameter(
                    description = "다운로드할 파일명"
            )
            @PathVariable
            String filename,
            HttpServletRequest request,
            Principal principal
    ) {
        try {
            User user = getUser(principal);

            return switch (
                    fileAccessService.forDownload(
                            filename,
                            user.getId()
                    )
                    ) {
                case FileAccess.Stream stream ->
                        attachmentResponse(stream);

                case FileAccess.Redirect redirect ->
                        redirectResponse(redirect);
            };

        } catch (Exception e) {
            log.error(
                    "파일 다운로드 중 에러 발생: {}",
                    filename,
                    e
            );

            return handleFileError(e);
        }
    }

    /**
     * 파일 미리보기.
     *
     * CloudFront 활성화 시 FileAccessService에서
     * Signed URL을 만든 뒤 302 Redirect한다.
     */
    @GetMapping("/view/{filename:.+}")
    public ResponseEntity<?> viewFile(
            @PathVariable
            String filename,
            HttpServletRequest request,
            Principal principal
    ) {
        try {
            User user = getUser(principal);

            return switch (
                    fileAccessService.forView(
                            filename,
                            user.getId()
                    )
                    ) {
                case FileAccess.Stream stream ->
                        inlineResponse(stream);

                case FileAccess.Redirect redirect ->
                        redirectResponse(redirect);
            };

        } catch (
                PreviewNotSupportedException e
        ) {
            return ResponseEntity
                    .status(
                            HttpStatus.UNSUPPORTED_MEDIA_TYPE
                    )
                    .body(
                            Map.of(
                                    "success",
                                    false,
                                    "message",
                                    safeMessage(e)
                            )
                    );

        } catch (Exception e) {
            log.error(
                    "파일 미리보기 중 에러 발생: {}",
                    filename,
                    e
            );

            return handleFileError(e);
        }
    }

    /**
     * Soft delete.
     *
     * 실제 삭제 정책은 FileService 구현에서 수행한다.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteFile(
            @PathVariable String id,
            Principal principal
    ) {
        try {
            User user = getUser(principal);

            boolean deleted =
                    fileService.deleteFile(
                            id,
                            user.getId()
                    );

            if (deleted) {
                return ResponseEntity.ok(
                        Map.of(
                                "success",
                                true,
                                "message",
                                "파일이 삭제되었습니다."
                        )
                );
            }

            return ResponseEntity
                    .badRequest()
                    .body(
                            Map.of(
                                    "success",
                                    false,
                                    "message",
                                    "파일 삭제에 실패했습니다."
                            )
                    );

        } catch (RuntimeException e) {
            log.error(
                    "파일 삭제 중 에러 발생: {}",
                    id,
                    e
            );

            String errorMessage =
                    e.getMessage();

            if (errorMessage != null
                    && errorMessage.contains(
                    "찾을 수 없습니다"
            )) {

                return ResponseEntity
                        .status(HttpStatus.NOT_FOUND)
                        .body(
                                Map.of(
                                        "success",
                                        false,
                                        "message",
                                        "파일을 찾을 수 없습니다."
                                )
                        );
            }

            if (errorMessage != null
                    && errorMessage.contains(
                    "권한"
            )) {

                return ResponseEntity
                        .status(HttpStatus.FORBIDDEN)
                        .body(
                                Map.of(
                                        "success",
                                        false,
                                        "message",
                                        "파일을 삭제할 권한이 없습니다."
                                )
                        );
            }

            Map<String, Object> errorResponse =
                    new HashMap<>();

            errorResponse.put(
                    "success",
                    false
            );

            errorResponse.put(
                    "message",
                    "파일 삭제 중 오류가 발생했습니다."
            );

            errorResponse.put(
                    "error",
                    errorMessage
            );

            return ResponseEntity
                    .status(
                            HttpStatus.INTERNAL_SERVER_ERROR
                    )
                    .body(errorResponse);
        }
    }

    private ResponseEntity<?> attachmentResponse(
            FileAccess.Stream stream
    ) {
        String contentDisposition =
                String.format(
                        "attachment; filename*=UTF-8''%s",
                        encodeFilename(
                                stream.originalname()
                        )
                );

        return ResponseEntity
                .ok()
                .contentType(
                        MediaType.parseMediaType(
                                stream.contentType()
                        )
                )
                .contentLength(stream.size())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        contentDisposition
                )
                .header(
                        HttpHeaders.CACHE_CONTROL,
                        "private, no-cache, no-store, must-revalidate"
                )
                .header(
                        HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                        "Content-Disposition"
                )
                .body(stream.resource());
    }

    private ResponseEntity<?> inlineResponse(
            FileAccess.Stream stream
    ) {
        String contentDisposition =
                String.format(
                        "inline; filename=\"%s\"; filename*=UTF-8''%s",
                        stream.originalname(),
                        encodeFilename(
                                stream.originalname()
                        )
                );

        return ResponseEntity
                .ok()
                .contentType(
                        MediaType.parseMediaType(
                                stream.contentType()
                        )
                )
                .contentLength(stream.size())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        contentDisposition
                )
                .body(stream.resource());
    }

    private ResponseEntity<?> redirectResponse(
            FileAccess.Redirect redirect
    ) {
        return ResponseEntity
                .status(HttpStatus.FOUND)
                .location(redirect.location())
                .build();
    }

    /**
     * 최신 main의 기존 에러 매핑을 유지하면서
     * DeletedFileException만 410으로 추가한다.
     */
    private ResponseEntity<?> handleFileError(
            Exception e
    ) {
        String errorMessage =
                e.getMessage();

        HttpStatus status =
                HttpStatus.INTERNAL_SERVER_ERROR;

        String responseMessage =
                "파일 처리 중 오류가 발생했습니다.";

        if (e instanceof DeletedFileException) {
            status = HttpStatus.GONE;
            responseMessage =
                    "삭제된 파일입니다.";

        } else if (errorMessage != null) {

            if (errorMessage.contains("잘못된 파일명")
                    || errorMessage.contains(
                    "Invalid filename"
            )) {

                status = HttpStatus.BAD_REQUEST;
                responseMessage =
                        "잘못된 파일명입니다.";

            } else if (errorMessage.contains("인증")
                    || errorMessage.contains(
                    "Authentication"
            )) {

                status = HttpStatus.UNAUTHORIZED;
                responseMessage =
                        "인증이 필요합니다.";

            } else if (
                    errorMessage.contains(
                            "잘못된 파일 경로"
                    )
                            || errorMessage.contains(
                            "Invalid file path"
                    )
            ) {

                status = HttpStatus.BAD_REQUEST;
                responseMessage =
                        "잘못된 파일 경로입니다.";

            } else if (
                    errorMessage.contains(
                            "찾을 수 없습니다"
                    )
                            || errorMessage.contains(
                            "not found"
                    )
            ) {

                status = HttpStatus.NOT_FOUND;
                responseMessage =
                        "파일을 찾을 수 없습니다.";

            } else if (
                    errorMessage.contains(
                            "메시지를 찾을 수 없습니다"
                    )
            ) {

                status = HttpStatus.NOT_FOUND;
                responseMessage =
                        "파일 메시지를 찾을 수 없습니다.";

            } else if (
                    errorMessage.contains("권한")
                            || errorMessage.contains(
                            "Unauthorized"
                    )
            ) {

                status = HttpStatus.FORBIDDEN;
                responseMessage =
                        "파일에 접근할 권한이 없습니다.";
            }
        }

        return ResponseEntity
                .status(status)
                .body(
                        Map.of(
                                "success",
                                false,
                                "message",
                                responseMessage
                        )
                );
    }

    private User getUser(
            Principal principal
    ) {
        if (principal == null) {
            throw new RuntimeException(
                    "인증이 필요합니다."
            );
        }

        return userRepository
                .findByEmail(principal.getName())
                .orElseThrow(() ->
                        new UsernameNotFoundException(
                                "User not found: "
                                        + principal.getName()
                        )
                );
    }

    private Map<String, Object> toFileResponse(
            File file
    ) {
        Map<String, Object> fileData =
                new HashMap<>();

        fileData.put(
                "_id",
                file.getId()
        );

        fileData.put(
                "filename",
                file.getFilename()
        );

        fileData.put(
                "originalname",
                file.getOriginalname()
        );

        fileData.put(
                "mimetype",
                file.getMimetype()
        );

        fileData.put(
                "size",
                file.getSize()
        );

        fileData.put(
                "uploadDate",
                file.getUploadDate()
        );

        fileData.put(
                "deleted",
                file.isDeleted()
        );

        return fileData;
    }

    private String encodeFilename(
            String originalFilename
    ) {
        return URLEncoder
                .encode(
                        originalFilename,
                        StandardCharsets.UTF_8
                )
                .replaceAll(
                        "\\+",
                        "%20"
                );
    }

    private String safeMessage(
            Exception e
    ) {
        return e.getMessage() != null
                ? e.getMessage()
                : "요청 처리 중 오류가 발생했습니다.";
    }

    public record DirectUploadPresignRequest(
            String originalname,
            String mimetype,
            long size
    ) {
    }

    public record DirectUploadCompleteRequest(
            String key,
            String originalname,
            String mimetype,
            long size
    ) {
    }
}
