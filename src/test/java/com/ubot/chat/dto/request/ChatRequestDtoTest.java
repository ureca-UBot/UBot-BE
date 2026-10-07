package com.ubot.chat.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.ubot.ai.dto.Location;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class ChatRequestDtoTest {
	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	void 내_위치_표시가_없으면_좌표가_와도_위치를_쓰지_않는다() {
		var request = new ChatRequestDto("근처 매장", 37.5, 127.0, null);

		assertThat(request.useMyLocation()).isFalse();
		assertThat(request.myLocation()).isNull();
		assertThat(validator.validate(request)).isEmpty();
	}

	@Test
	void 내_위치_기준_재요청이면_좌표를_Location으로_돌려준다() {
		var request = new ChatRequestDto("근처 매장", 37.5, 127.0, true);

		assertThat(request.myLocation()).isEqualTo(new Location(37.5, 127.0));
		assertThat(validator.validate(request)).isEmpty();
	}

	@Test
	void 내_위치_기준_요청인데_좌표가_없으면_검증에서_거부한다() {
		var request = new ChatRequestDto("근처 매장", null, null, true);

		assertThat(validator.validate(request))
				.extracting(violation -> violation.getMessage())
				.containsExactly("현재 위치로 찾기 요청에는 좌표가 있어야 합니다.");
	}
}
