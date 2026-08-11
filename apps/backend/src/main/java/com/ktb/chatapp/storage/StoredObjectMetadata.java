package com.ktb.chatapp.storage;

public record StoredObjectMetadata(
        long size,
        String contentType
) {
}