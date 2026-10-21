package com.ubot.counsel.history.model;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

// 조회 시점의 행 번호 대신 변경되지 않는 원본 정렬 키를 전달함
public record TimelineCursor(Instant occurredAt, int position, UUID sessionId, long itemOrder, int part, String id) {

	public static final UUID NO_SESSION = new UUID(0, 0);

	public String encode() {
		String value = occurredAt + "|" + position + "|" + sessionId + "|" + itemOrder + "|" + part + "|" + id;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	public static TimelineCursor decode(String encoded) {
		if (encoded == null || encoded.isEmpty()) return null;
		if (encoded.length() > 512) throw new IllegalArgumentException();
		try {
			String[] values = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8).split("\\|", -1);
			if (values.length != 6 || values[5].isEmpty()) throw new IllegalArgumentException();
			return new TimelineCursor(Instant.parse(values[0]), Integer.parseInt(values[1]), UUID.fromString(values[2]),
					Long.parseLong(values[3]), Integer.parseInt(values[4]), values[5]);
		} catch (RuntimeException invalid) {
			throw new IllegalArgumentException("타임라인 커서를 확인해 주세요.");
		}
	}
}
