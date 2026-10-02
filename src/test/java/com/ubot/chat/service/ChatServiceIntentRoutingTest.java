package com.ubot.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.ai.dto.AiAnswer;
import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.Location;
import com.ubot.ai.dto.StoreMapResult;
import com.ubot.ai.service.AiService;
import com.ubot.ai.tool.AiTool;
import com.ubot.chat.dto.response.ChatResponseDto;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.service.FaqVectorService;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import com.ubot.unanswered.service.UnansweredQuestionService;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("채팅 intent 분기 연결 테스트")
class ChatServiceIntentRoutingTest {
	private final FaqVectorService vector = mock(FaqVectorService.class);
	private final AiService ai = mock(AiService.class);
	private final ChatAttemptsService attempts = mock(ChatAttemptsService.class);
	private final Deque<Runnable> jobs = new ArrayDeque<>();
	private final AnswerAttemptsHistory attempt = AnswerAttemptsHistory.builder()
			.id(1L).userId(1L).question("근처 매장 알려줘").idempotencyKey("a".repeat(64))
			.attemptCount(1).status("PENDING").build();
	private final List<FaqSearchResponseDto> sources = List.of(
			new FaqSearchResponseDto(1L, "매장 찾기", "근처 매장을 안내합니다.", 0.9, Intent.STORE_DATA),
			new FaqSearchResponseDto(2L, "영업시간", "매장마다 다릅니다.", 0.8, Intent.GENERAL),
			new FaqSearchResponseDto(3L, "낮은 점수", "제외됩니다.", 0.5, Intent.GENERAL));
	private ChatService service;

	@BeforeEach
	void setUp() {
		service = new ChatService(vector, ai, attempts, mock(ForbiddenWordFilterService.class),
				mock(UnansweredQuestionService.class), ChatTestFixtures.collector(), jobs::add);
		ReflectionTestUtils.setField(service, "topK", 3);
		ReflectionTestUtils.setField(service, "confidenceThreshold", 0.75);
		when(attempts.createAnswerAttempt(1L, "근처 매장 알려줘")).thenReturn(attempt);
		when(attempts.createRetryAttempt(1L, attempt.getIdempotencyKey())).thenReturn(attempt);
		when(vector.getSimilarList("근처 매장 알려줘", 3)).thenReturn(sources);
		when(ai.generateAnswer(any(AnswerMaterials.class))).thenReturn(new AiAnswer("답변", null));
		when(attempts.saveAnswerSuccess(any(), any(), any())).thenAnswer(call -> {
			AnswerAttemptsHistory saved = call.getArgument(0);
			saved.succeed();
			return saved;
		});
	}

	@Test
	@DisplayName("threshold를 넘은 FAQ의 intent별로 자료를 모으고 요청 위치를 전달한다")
	void collectsMaterialsByIntentWithLocation() {
		service.createChat(1L, "근처 매장 알려줘", 37.5, 127.0);
		jobs.poll().run();

		AnswerMaterials materials = captureMaterials();
		assertThat(materials.question()).isEqualTo("근처 매장 알려줘");
		assertThat(materials.faqs()).extracting(FaqSearchResponseDto::faqId).containsExactly(2L);
		assertThat(materials.tools()).containsExactly(AiTool.STORE_SEARCH);
		assertThat(materials.location()).isEqualTo(new Location(37.5, 127.0));
		// faq_log는 intent와 관계없이 threshold를 넘은 검색 결과 전체로 남깁니다.
		verify(attempts).saveAnswerSuccess(attempt, "답변", sources.subList(0, 2));
	}

	@Test
	@DisplayName("도구가 조회한 매장 지도 정보를 성공 응답에 담는다")
	void returnsStoreMapInResponse() {
		var storeMap = new StoreMapResult(new Location(37.5, 127.0), null, 3.0, List.of());
		when(ai.generateAnswer(any(AnswerMaterials.class))).thenReturn(new AiAnswer("답변", storeMap));

		var task = service.createChat(1L, "근처 매장 알려줘", 37.5, 127.0);
		jobs.poll().run();

		ChatResponseDto response = task.result().join();
		assertThat(response.answer()).isEqualTo("답변");
		assertThat(response.storeMap()).isSameAs(storeMap);
	}

	@Test
	@DisplayName("재시도는 위치 없이 자료를 모은다")
	void retryHasNoLocation() {
		service.retryChat(1L, attempt.getIdempotencyKey());
		jobs.poll().run();

		assertThat(captureMaterials().location()).isNull();
	}

	private AnswerMaterials captureMaterials() {
		ArgumentCaptor<AnswerMaterials> materials = ArgumentCaptor.forClass(AnswerMaterials.class);
		verify(ai).generateAnswer(materials.capture());
		return materials.getValue();
	}
}
