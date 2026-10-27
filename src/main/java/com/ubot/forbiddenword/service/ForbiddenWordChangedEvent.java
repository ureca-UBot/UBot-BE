package com.ubot.forbiddenword.service;

/** 금지어 변경이 트랜잭션에 반영된 뒤 캐시를 갱신하기 위한 이벤트입니다. */
public record ForbiddenWordChangedEvent() {
}
