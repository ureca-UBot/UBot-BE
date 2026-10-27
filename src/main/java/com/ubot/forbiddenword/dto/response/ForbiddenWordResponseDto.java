package com.ubot.forbiddenword.dto.response;

import com.ubot.forbiddenword.entity.ForbiddenWord;
import com.ubot.forbiddenword.enums.ForbiddenWordStatus;

import java.time.LocalDateTime;

public record ForbiddenWordResponseDto(
		Long id,
		String word,
		ForbiddenWordStatus status,
		LocalDateTime updatedAt
) {
	public static ForbiddenWordResponseDto from(ForbiddenWord forbiddenWord) {
		return new ForbiddenWordResponseDto(
				forbiddenWord.getId(),
				forbiddenWord.getWord(),
				forbiddenWord.getStatus(),
				forbiddenWord.getUpdatedAt()
		);
	}
}
