package com.ktb.chatapp.exception;

public class DirectUploadNotSupportedException extends RuntimeException {

    public DirectUploadNotSupportedException() {
        super("현재 스토리지는 직접 업로드를 지원하지 않습니다.");
    }
}
