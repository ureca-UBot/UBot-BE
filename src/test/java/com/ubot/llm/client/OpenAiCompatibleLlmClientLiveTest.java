package com.ubot.llm.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

import com.ubot.ai.tool.AiTool;
import com.ubot.ai.tool.AiToolRegistry;
import com.ubot.ai.tool.NearbyStoreSearcher;
import com.ubot.ai.tool.StoreSearchRecorder;
import com.ubot.ai.tool.StoreTools;
import com.ubot.llm.config.LlmClientFactory;
import com.ubot.llm.dto.request.LlmMessageRequestDto;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.enums.LlmMessageRole;
import com.ubot.location.dto.LocationSearchResponse;
import com.ubot.location.service.LocationService;
import com.ubot.store.dto.NearbyStoreResponseDto;
import com.ubot.store.service.StoreService;

/**
 * 실제 OpenAI 호환 서버(vLLM)에 연결해 일반 답변과 Tool Calling을 확인합니다.
 * 서버가 필요하므로 LLM_LIVE_BASE_URL(예: http://localhost:8000/v1)이 있을 때만 실행합니다.
 * vLLM 이미지 버전이나 실행 옵션을 바꾼 뒤 다시 돌려 확인합니다.
 */
@EnabledIfEnvironmentVariable(named = "LLM_LIVE_BASE_URL", matches = ".+")
class OpenAiCompatibleLlmClientLiveTest {

	private static final String STORE_QUESTION = "강남역 근처 매장 알려줘";

	private final LlmClient client =
	        new LlmClientFactory().createOpenAiCompatible(
	                RestClient.builder(),
	                System.getenv("LLM_LIVE_BASE_URL"),
	                System.getenv()
	                        .getOrDefault(
	                                "LLM_LIVE_MODEL",
	                                "ubot-chat"
	                        ),
	                Duration.ofSeconds(3),
	                Duration.ofSeconds(120)
	        );

	@Test
	void answersPlainQuestionWithoutThinkingText() {
		var answer = client.generateAnswer(new LlmRequestDto(List.of(
				new LlmMessageRequestDto(LlmMessageRole.SYSTEM, "제공된 FAQ만 근거로 한국어로 짧게 답하세요."),
				new LlmMessageRequestDto(LlmMessageRole.USER,
						"사용자 질문:\n유심 재발급 방법\n\nFAQ 원문:\n[FAQ ID: 12]\n질문: 유심 재발급 방법\n"
								+ "답변: 유심 재발급은 신분증을 지참하고 가까운 매장을 방문하면 가능합니다."))));

		assertThat(answer.answer()).isNotBlank().doesNotContain("<think>");
	}

	@Test
	void callsStoreToolAndAnswersWithItsResult() {
		StoreService storeService = mock(StoreService.class);
		LocationService locationService = mock(LocationService.class);
		when(locationService.search("강남역")).thenReturn(List.of(
				new LocationSearchResponse("강남역", "서울 강남구", "서울 강남구 강남대로", 37.5, 127.0)));
		when(storeService.getNearbyStoreList(37.5, 127.0, 3.0, List.of(), 5)).thenReturn(List.of(
				new NearbyStoreResponseDto(12L, "강남점", "서울", "강남구", "서울 강남구 테헤란로 1",
						"02-123-4567", "10:00~21:00", 37.49, 127.02, 0.42)));
		var registry = new AiToolRegistry(
				new StoreTools(new NearbyStoreSearcher(storeService, 3.0, 5), locationService));
		var recorder = new StoreSearchRecorder();

		var answer = client.generateAnswer(new LlmRequestDto(List.of(
				new LlmMessageRequestDto(LlmMessageRole.SYSTEM,
						"당신은 통신 서비스 상담봇입니다. 매장 조회 도구가 주어지면 필요할 때 호출하고, 도구 결과만 근거로 한국어로 답하세요."),
				new LlmMessageRequestDto(LlmMessageRole.USER, STORE_QUESTION)))
				.withTools(registry.resolve(Set.of(AiTool.STORE_SEARCH)), Map.of(
						StoreTools.QUESTION, STORE_QUESTION,
						StoreTools.RECORDER, recorder)));

		// 모델이 도구를 부르고, 그 결과를 받은 뒤 최종 답변을 만들었는지 확인합니다.
		verify(storeService).getNearbyStoreList(37.5, 127.0, 3.0, List.of(), 5);
		assertThat(recorder.result()).isPresent();
		assertThat(answer.answer()).isNotBlank().doesNotContain("<think>");
	}
}
