package com.ubot.faq.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.ubot.llm.dto.request.LlmMessageRequestDto;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.enums.LlmMessageRole;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.llm.service.LlmService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class FaqSimilarQuestionService {
	private static final int MAX_QUESTIONS = 10;
	private static final int MAX_QUESTION_LENGTH = 1000;
	private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:\\d+[.)]|[-*])\\s*(.+?)\\s*$");

	private final LlmService llmService;

	public List<String> generate(String question) {
		String prompt = """
				다음 질문을 사용자가 실제로 말할 법한 서로 다른 한국어 표현으로 최대 10개 바꿔 쓰세요.
				원문의 의미를 유지하고, 원문에 없는 정보나 조건을 추가하지 마세요.
				원문을 그대로 반복하거나 같은 표현을 중복해서 쓰지 마세요. 충분히 다른 표현이 없으면 10개보다 적게 출력하세요.
				설명 없이 한 줄에 질문 하나씩 번호 목록으로 출력하세요.

				원문: %s
				""".formatted(question);

		String answer = llmService.generateAnswer(new LlmRequestDto(List.of(
				new LlmMessageRequestDto(LlmMessageRole.USER, prompt)))).answer();

		Set<String> seen = new HashSet<>();
		seen.add(normalize(question));
		List<String> questions = new ArrayList<>();
		for (String line : answer.split("\\R")) {
			Matcher match = LIST_ITEM.matcher(line);
			if (!match.matches()) {
				continue;
			}
			String candidate = match.group(1).strip();
			String normalized = normalize(candidate);
			if (candidate.isEmpty() || candidate.length() > MAX_QUESTION_LENGTH || !seen.add(normalized)) {
				continue;
			}
			questions.add(candidate);
			if (questions.size() == MAX_QUESTIONS) {
				break;
			}
		}
		if (questions.isEmpty()) {
			throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);
		}
		return questions;
	}

	private String normalize(String question) {
		return question.strip().replaceAll("\\s+", " ");
	}
}
