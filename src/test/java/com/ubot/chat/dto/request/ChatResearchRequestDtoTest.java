package com.ubot.chat.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.ubot.ai.dto.Location;
import com.ubot.faq.enums.Intent;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("재검색 요청 DTO 검증 테스트")
class ChatResearchRequestDtoTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("의도만 보내면 유효하고 위치는 null이다")
    void intentOnlyIsValid() {
        var dto = new ChatResearchRequestDto(Intent.GENERAL, null, null);

        assertThat(validator.validate(dto)).isEmpty();
        assertThat(dto.myLocation()).isNull();
    }

    @Test
    @DisplayName("좌표를 함께 보내면 Location으로 돌려준다")
    void coordinatesPairBecomesLocation() {
        var dto = new ChatResearchRequestDto(Intent.STORE_DATA, 37.5, 127.0);

        assertThat(validator.validate(dto)).isEmpty();
        assertThat(dto.myLocation()).isEqualTo(new Location(37.5, 127.0));
    }

    @Test
    @DisplayName("의도가 없으면 거절한다")
    void intentIsRequired() {
        assertThat(validator.validate(new ChatResearchRequestDto(null, null, null))).isNotEmpty();
    }

    @Test
    @DisplayName("위도와 경도 중 하나만 보내면 거절한다")
    void latitudeAndLongitudeMustComeTogether() {
        assertThat(validator.validate(new ChatResearchRequestDto(Intent.STORE_DATA, 37.5, null))).isNotEmpty();
        assertThat(validator.validate(new ChatResearchRequestDto(Intent.STORE_DATA, null, 127.0))).isNotEmpty();
    }

    @Test
    @DisplayName("좌표 범위를 벗어나면 거절한다")
    void coordinatesMustBeInRange() {
        assertThat(validator.validate(new ChatResearchRequestDto(Intent.STORE_DATA, 91.0, 127.0))).isNotEmpty();
        assertThat(validator.validate(new ChatResearchRequestDto(Intent.STORE_DATA, 37.5, -181.0))).isNotEmpty();
    }
}