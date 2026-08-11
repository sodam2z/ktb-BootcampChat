package com.ktb.chatapp.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "files")
public class File {

    @Id
    private String id;

    // 파일 조회 API는 저장된 filename으로 메타데이터를 찾으므로 다운로드/미리보기 지연을 줄인다.
    @Indexed(name = "filename_idx")
    private String filename;

    private String originalname;

    private String mimetype;

    private long size;

    private String path;

    @Field("user")
    private String user;

    @Field("uploadDate")
    @CreatedDate
    private LocalDateTime uploadDate;

    /**
     * 미리보기 지원 여부 확인
     */
    public boolean isPreviewable() {
        List<String> previewableTypes = Arrays.asList(
            "image/jpeg", "image/png", "image/gif", "image/webp",
            "video/mp4", "video/webm",
            "audio/mpeg", "audio/wav",
            "application/pdf"
        );
        return previewableTypes.contains(this.mimetype);
    }

    /**
     * Content-Disposition 헤더 생성
     */
    public String getContentDisposition(String type) {
        String encodedFilename = URLEncoder.encode(this.originalname, StandardCharsets.UTF_8)
                .replaceAll("\\+", "%20");
        
        return String.format(
            "%s; filename=\"%s\"; filename*=UTF-8''%s",
            type,
            this.originalname,
            encodedFilename
        );
    }

    /**
     * 파일 URL 생성
     */
    public String getFileUrl(String type) {
        return String.format("/api/files/%s/%s",
            type,
            URLEncoder.encode(this.filename, StandardCharsets.UTF_8));
    }
}
