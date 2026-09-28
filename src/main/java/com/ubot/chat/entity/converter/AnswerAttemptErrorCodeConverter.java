package com.ubot.chat.entity.converter;

import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.common.ErrorCode;
import com.ubot.embedding.exception.EmbeddingErrorCode;
import com.ubot.llm.exception.LlmErrorCode;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** 답변 시도의 오류 객체와 기존 error_code 문자열 컬럼을 변환합니다. */
@Converter
public class AnswerAttemptErrorCodeConverter implements AttributeConverter<ErrorCode, String> {
	@Override
	public String convertToDatabaseColumn(ErrorCode attribute) {
		return attribute == null ? null : resolve(attribute.getCode()).getCode();
	}

	@Override
	public ErrorCode convertToEntityAttribute(String dbData) {
		return dbData == null ? null : resolve(dbData);
	}

	private ErrorCode resolve(String value) {
		for (ChatErrorCode code : ChatErrorCode.values()) {
			if (code.getCode().equals(value)) {
				return code;
			}
		}
		for (EmbeddingErrorCode code : EmbeddingErrorCode.values()) {
			if (code.getCode().equals(value)) {
				return code;
			}
		}
		for (LlmErrorCode code : LlmErrorCode.values()) {
			// 번호 도입 전에 enum 이름으로 저장한 기존 기록도 읽습니다.
			if (code.getCode().equals(value) || code.name().equals(value)) {
				return code;
			}
		}
		throw new IllegalArgumentException("Unknown answer attempt error code: " + value);
	}
}
