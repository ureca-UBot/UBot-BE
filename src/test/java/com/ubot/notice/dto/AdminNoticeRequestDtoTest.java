package com.ubot.notice.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ubot.notice.dto.request.AdminNoticeRequestDto;
import com.ubot.notice.enums.NoticeType;

@DisplayName("관리자 공지·장애 요청 DTO 테스트")
class AdminNoticeRequestDtoTest {

    @Test
    @DisplayName("제목과 본문의 앞뒤 공백(전각 공백 포함)을 제거한다")
    void stripsTitleAndContent() {
        AdminNoticeRequestDto request = new AdminNoticeRequestDto(
                NoticeType.NOTICE, "\u3000제목  ", " \u2003내용\n", null, null);

        assertThat(request.title()).isEqualTo("제목");
        assertThat(request.content()).isEqualTo("내용");
    }

    @Test
    @DisplayName("전각 공백뿐인 값은 빈 문자열이 되어 @NotBlank로 거절된다")
    void unicodeBlankBecomesEmpty() {
        AdminNoticeRequestDto request = new AdminNoticeRequestDto(
                NoticeType.NOTICE, "\u3000\u2003", "내용", null, null);

        assertThat(request.title()).isEmpty();
    }

    @Test
    @DisplayName("null은 그대로 두어 @NotBlank가 거절하게 한다")
    void keepsNull() {
        AdminNoticeRequestDto request = new AdminNoticeRequestDto(NoticeType.NOTICE, null, null, null, null);

        assertThat(request.title()).isNull();
        assertThat(request.content()).isNull();
    }
}