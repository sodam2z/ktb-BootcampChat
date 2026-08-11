package com.ktb.chatapp.exception;

public class DeletedFileException
        extends RuntimeException {

    public DeletedFileException() {
        super("삭제된 파일입니다.");
    }
}