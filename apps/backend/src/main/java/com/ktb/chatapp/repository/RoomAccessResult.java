package com.ktb.chatapp.repository;

/** 방 존재 여부와 참가 여부를 한 번의 MongoDB 조회로 판정한 결과. */
public record RoomAccessResult(boolean participant) {
}
