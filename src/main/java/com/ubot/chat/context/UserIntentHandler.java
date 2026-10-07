package com.ubot.chat.context;

import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.ContextSection;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.user.dto.response.UserResponseDto;
import com.ubot.user.exception.UserException;
import com.ubot.user.service.UserService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 로그인 사용자의 정보를 직접 조회해 참고 자료 섹션으로 넣습니다. */
@Component
@RequiredArgsConstructor
public class UserIntentHandler implements IntentHandler {
	static final String SECTION_TITLE = "사용자 정보";
	static final String NOT_FOUND_CONTENT = "사용자 정보를 찾을 수 없습니다.";
	// DB에 없는 값을 LLM이 지어내지 않고 ABSTAIN하도록 알립니다.
	static final String UNAVAILABLE_NOTICE = "요금, 데이터 사용량, 미납 정보는 제공되지 않습니다.";

	private final UserService userService;

	@Override
	public Intent intent() {
		return Intent.USER_DATA;
	}

	@Override
	public void contribute(ChatContext context, List<FaqSearchResponseDto> results, AnswerMaterials.Builder materials) {
		if (context.userId() == null) {
			materials.addSection(new ContextSection(SECTION_TITLE, NOT_FOUND_CONTENT));
			return;
		}
		try {
			UserResponseDto user = userService.getActiveUser(context.userId());
			materials.addSection(new ContextSection(SECTION_TITLE, format(user)));
		} catch (UserException exception) {
			materials.addSection(new ContextSection(SECTION_TITLE, NOT_FOUND_CONTENT));
		}
	}

	private String format(UserResponseDto user) {
		// 이메일은 답변에 필요하지 않으므로 넣지 않습니다.
		return "이름: " + valueOrUnknown(user.name()) + "\n"
				+ "거주 지역: " + valueOrUnknown(user.residenceArea()) + "\n"
				+ "가입일: " + (user.createdAt() == null ? "정보 없음" : user.createdAt().toLocalDate()) + "\n"
				+ UNAVAILABLE_NOTICE;
	}

	private String valueOrUnknown(String value) {
		return StringUtils.hasText(value) ? value : "정보 없음";
	}
}
