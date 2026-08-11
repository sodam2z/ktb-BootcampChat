package com.ktb.chatapp.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 파일 읽기 경로에서 발생한 조회·인가 실패를 문자열이 아닌 코드로 전달한다.
 */
@Getter
public class FileAccessException extends RuntimeException {

    private final Reason reason;

    public FileAccessException(Reason reason) {
        super(reason.getMessage());
        this.reason = reason;
    }

    @Getter
    public enum Reason {
        FILE_NOT_FOUND("file_not_found", "파일을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
        MESSAGE_NOT_FOUND("message_not_found", "파일 메시지를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
        ROOM_NOT_FOUND("room_not_found", "파일이 속한 채팅방을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
        NOT_PARTICIPANT("not_participant", "파일에 접근할 권한이 없습니다.", HttpStatus.FORBIDDEN);

        private final String code;
        private final String message;
        private final HttpStatus status;

        Reason(String code, String message, HttpStatus status) {
            this.code = code;
            this.message = message;
            this.status = status;
        }
    }
}
