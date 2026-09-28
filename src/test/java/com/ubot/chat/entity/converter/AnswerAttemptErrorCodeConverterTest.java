package com.ubot.chat.entity.converter;

import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.LlmErrorCodeAdapter;
import com.ubot.common.ErrorCode;
import com.ubot.embedding.exception.EmbeddingErrorCode;
import com.ubot.llm.enums.LlmErrorCode;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnswerAttemptErrorCodeConverterTest {
	private final AnswerAttemptErrorCodeConverter converter = new AnswerAttemptErrorCodeConverter();

	@ParameterizedTest
	@MethodSource("storedErrorCodes")
	void preservesExistingDatabaseCodesAndRestoresErrorObjects(String storedCode, ErrorCode errorCode) {
		assertThat(converter.convertToDatabaseColumn(errorCode)).isEqualTo(storedCode);

		ErrorCode restored = converter.convertToEntityAttribute(storedCode);
		assertThat(restored).isEqualTo(errorCode);
		assertThat(restored.getCode()).isEqualTo(storedCode);
		assertThat(restored.getMessage()).isEqualTo(errorCode.getMessage());
		assertThat(restored.getStatus()).isEqualTo(errorCode.getStatus());
	}

	@Test
	void preservesNullWhenAttemptHasNoError() {
		assertThat(converter.convertToDatabaseColumn(null)).isNull();
		assertThat(converter.convertToEntityAttribute(null)).isNull();
	}

	@Test
	void rejectsUnregisteredCodesOnReadAndWrite() {
		ErrorCode unknown = mock(ErrorCode.class);
		when(unknown.getCode()).thenReturn("UNKNOWN");

		assertThatIllegalArgumentException()
				.isThrownBy(() -> converter.convertToEntityAttribute("UNKNOWN"));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> converter.convertToDatabaseColumn(unknown));
	}

	private static Stream<Arguments> storedErrorCodes() {
		return Stream.of(
				Arguments.of("CHAT-010", ChatErrorCode.VECTOR_SEARCH_FAILED),
				Arguments.of("EM-003", EmbeddingErrorCode.EMBEDDING_TIMEOUT),
				Arguments.of("LLM_TIMEOUT", new LlmErrorCodeAdapter(LlmErrorCode.LLM_TIMEOUT))
		);
	}
}
