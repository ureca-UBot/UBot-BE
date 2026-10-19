package com.ubot.chat.context;

import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.ContextSection;
import com.ubot.ai.dto.UserDataSummary;
import com.ubot.ai.tool.AiTool;
import com.ubot.ai.tool.UserDataSummarizer;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.user.exception.UserException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 로그인 사용자의 가입 정보(프로필, 요금제, 최근 사용량·청구, 약정, 부가서비스·결합·로밍)를 미리 조회해 넣고,
 * 그보다 이전 달의 사용량·청구서는 LLM이 도구로 조회하게 합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserIntentHandler implements IntentHandler {
	static final String SECTION_TITLE = "사용자 정보";
	static final String LOGIN_REQUIRED_CONTENT =
			"로그인한 사용자만 가입 정보를 확인할 수 있습니다. 로그인 후 다시 질문하도록 안내하세요.";
	static final String NOT_FOUND_CONTENT = "사용자 정보를 찾을 수 없습니다.";
	static final String LOOKUP_FAILED_CONTENT = "사용자 정보를 불러오지 못했습니다. "
			+ "프로필, 요금제, 최근 사용량·청구, 미납, 약정, 부가서비스·결합·로밍 정보는 지금 확인할 수 없다고 안내하세요.";

	private final UserDataSummarizer userDataSummarizer;

	@Override
	public Intent intent() {
		return Intent.USER_DATA;
	}

	@Override
	public void contribute(ChatContext context, List<FaqSearchResponseDto> results, AnswerMaterials.Builder materials) {
		// 비회원에게는 개인 정보 도구를 붙이지 않습니다.
		if (context.userId() == null) {
			materials.addSection(new ContextSection(SECTION_TITLE, LOGIN_REQUIRED_CONTENT));
			return;
		}
		try {
			UserDataSummary summary = userDataSummarizer.summarize(context.userId());
			materials.addSection(new ContextSection(SECTION_TITLE, userDataSummarizer.format(summary)));
			// 답변에 쓰인 기록을 화면 카드로 고를 수 있게 조회 결과 객체도 넘깁니다. LLM에는 들어가지 않습니다.
			materials.userDataSummary(summary);
		} catch (UserException exception) {
			// 탈퇴했거나 없는 사용자는 도구로 조회해도 결과가 없으므로 도구를 붙이지 않습니다.
			materials.addSection(new ContextSection(SECTION_TITLE, NOT_FOUND_CONTENT));
			return;
		} catch (RuntimeException exception) {
			// 미리 조회가 실패해도 답변 전체를 실패시키지 않습니다. 도구는 요약에 없는 지난 달 사용량·청구서만
			// 조회하므로 실패한 요약을 대신하지 못하고, 그 질문에 답할 수 있도록만 켜 둡니다.
			log.warn("사용자 정보 미리 조회 실패: userId={}", context.userId(), exception);
			materials.addSection(new ContextSection(SECTION_TITLE, LOOKUP_FAILED_CONTENT));
		}
		materials.enableTool(AiTool.USER_DATA_LOOKUP);
	}
}
