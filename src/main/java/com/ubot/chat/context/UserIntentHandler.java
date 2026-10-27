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
import java.util.regex.Pattern;
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
	// 출력 가드가 이 섹션의 조회 성공·실패를 구분할 때도 씁니다.
	public static final String SECTION_TITLE = "사용자 정보";
	// 조회하지 못한 사유별 안내(로그인 등)는 코드가 붙이므로(AiService) 모델에게 쓰게 하지 않고 사실만 알립니다.
	// 출력 가드와 AiService는 이 문구를 그대로 비교해 조회 실패를 가립니다.
	public static final String LOGIN_REQUIRED_CONTENT = "로그인하지 않은 사용자라 가입 정보를 조회할 수 없습니다.";
	public static final String NOT_FOUND_CONTENT = "사용자 정보를 찾을 수 없습니다.";
	public static final String LOOKUP_FAILED_CONTENT = "사용자 정보를 불러오지 못했습니다. "
			+ "프로필, 요금제, 최근 사용량·청구, 미납, 약정, 부가서비스·결합·로밍 정보는 지금 확인할 수 없습니다.";

	// "내 데이터 사용량"처럼 자기를 가리키는 말과 회원 기록 항목이 함께 있으면 본인 정보를 묻는 질문으로 봅니다.
	// "제일", "제공"의 "제"가 걸리지 않게 앞뒤가 단어 경계인 자기 지칭만 봅니다.
	private static final Pattern SELF_REFERENCE = Pattern.compile("(?<![가-힣])(?:내|제|나의|저의|내가|제가)(?=\\s)");
	// 회원 기록 항목 없이도 본인을 가리키는 말입니다. "나 무슨 요금제 써?", "나한테 맞는 요금제"처럼 쓰입니다.
	private static final Pattern SELF_MENTION = Pattern.compile(
			"(?<![가-힣])(?:내|제|나|저|나의|저의|내가|제가|나한테|저한테|나에게|저에게|우리)(?![가-힣])");
	private static final Pattern OWN_RECORD =Pattern.compile("사용량|남은\\s*데이터|잔여|요금|청구|납부|미납|약정|할부");

	private final UserDataSummarizer userDataSummarizer;

	@Override
	public Intent intent() {
		return Intent.USER_DATA;
	}

	/**
	 * 검색된 FAQ의 intent와 상관없이 본인 정보를 묻는 질문인지입니다. "내 데이터 사용량 알려줘"에 "사용량 확인 방법" 같은
	 * 일반 FAQ만 걸리면 사용자 정보가 붙지 않아 방법 안내로 답하므로, ChatContextCollector가 이 판정으로 이 핸들러를 더 실행합니다.
	 */
	public static boolean asksOwnData(String question) {
		return question != null && SELF_REFERENCE.matcher(question).find() && OWN_RECORD.matcher(question).find();
	}

	/** 질문에 본인을 가리키는 말이 있는지입니다. 회원 기록 항목은 보지 않습니다. */
	public static boolean mentionsSelf(String question) {
		return question != null && SELF_MENTION.matcher(question).find();
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
