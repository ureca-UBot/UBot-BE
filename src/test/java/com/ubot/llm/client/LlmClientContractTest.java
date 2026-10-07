package com.ubot.llm.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.ai.tool.AiTool;
import com.ubot.ai.tool.AiToolRegistry;
import com.ubot.ai.tool.NearbyStoreSearcher;
import com.ubot.ai.tool.StoreSearchRecorder;
import com.ubot.ai.tool.StoreTools;
import com.ubot.llm.dto.request.LlmMessageRequestDto;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.enums.LlmMessageRole;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.location.dto.LocationSearchResponse;
import com.ubot.location.service.LocationService;
import com.ubot.store.dto.NearbyStoreResponseDto;
import com.ubot.store.service.StoreService;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * LlmClient 구현체가 UBot 관점에서 같은 동작을 하는지 확인하는 공통 계약입니다.
 * 구현체별 테스트는 서버 주소와 응답 형식만 채우고, 검증은 여기 있는 테스트를 그대로 물려받습니다.
 */
abstract class LlmClientContractTest {

	protected static final String MODEL_NAME = "contract-test-model";

	private static final String STORE_QUESTION = "강남역 근처 매장 알려줘";
	private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

	private final StoreService storeService = mock(StoreService.class);
	private final LocationService locationService = mock(LocationService.class);
	private final StoreSearchRecorder recorder = new StoreSearchRecorder();

	private StubLlmServer server;

	/** 구현체가 호출하는 채팅 API 경로입니다. */
	protected abstract String chatPath();

	/** serverUrl은 테스트 서버 주소(http://127.0.0.1:포트)입니다. */
	protected abstract LlmClient createClient(String serverUrl, String modelName, Duration readTimeout);

	/** 최종 답변이 담긴 응답 본문입니다. */
	protected abstract String answerResponse(String content);

	/** 길이 제한으로 잘린 답변의 응답 본문입니다. */
	protected abstract String truncatedResponse(String content);

	/** LLM이 도구 호출을 요청하는 응답 본문입니다. */
	protected abstract String toolCallResponse(String toolName, Map<String, Object> arguments);

	protected static String toJson(Object value) {
		return JSON_MAPPER.writeValueAsString(value);
	}

	@BeforeEach
	void startServer() throws IOException {
		server = new StubLlmServer(chatPath());
		when(locationService.search("강남역")).thenReturn(List.of(
				new LocationSearchResponse("강남역", "서울 강남구", "서울 강남구 강남대로", 37.5, 127.0)));
		when(storeService.getNearbyStoreList(37.5, 127.0, 3.0, List.of(), 5)).thenReturn(List.of(
				new NearbyStoreResponseDto(12L, "강남점", "서울", "강남구", "서울 강남구 테헤란로 1",
						"02-123-4567", "10:00~21:00", 37.49, 127.02, 0.42)));
	}

	@AfterEach
	void stopServer() {
		server.close();
	}

	@Test
	void returnsAnswerForPlainQuestion() {
		server.respond(answerResponse("가까운 매장에서 재발급할 수 있습니다."));

		var answer = client().generateAnswer(question("유심 재발급 방법"));

		assertThat(answer.answer()).isEqualTo("가까운 매장에서 재발급할 수 있습니다.");
		assertThat(server.requests()).hasSize(1);
	}

	@Test
	void executesToolAndReturnsFinalAnswer() {
		server.respond(toolCallResponse("findNearbyStores", Map.of("place", "강남역")));
		server.respond(answerResponse("강남점이 가까워요."));

		var answer = client().generateAnswer(storeQuestion());

		assertThat(answer.answer()).isEqualTo("강남점이 가까워요.");
		// LLM이 넘긴 장소명으로 매장을 조회하고, 요청이 넣은 recorder에 결과가 남아 화면에 전달할 수 있습니다.
		verify(storeService).getNearbyStoreList(37.5, 127.0, 3.0, List.of(), 5);
		assertThat(recorder.result()).hasValueSatisfying(result -> assertThat(result.stores())
				.extracting(NearbyStoreResponseDto::storeId).containsExactly(12L));
		// 도구 결과를 붙여 LLM을 한 번 더 호출해 최종 답변을 받습니다.
		assertThat(server.requests()).hasSize(2);
		assertThat(server.requests().get(1).toString()).contains("[매장 ID: 12] 강남점");
	}

	@Test
	void stopsExecutingToolAfterCallLimitAndLetsModelFinish() {
		// LLM이 같은 도구를 계속 부르는 경우입니다. 도구당 3번까지만 실행하고,
		// 4번째 요청에는 실행 대신 한도 초과를 도구 결과로 돌려준 뒤 LLM이 답변을 마무리합니다.
		for (int call = 0; call < 4; call++) {
			server.respond(toolCallResponse("findNearbyStores", Map.of("place", "강남역")));
		}
		server.respond(answerResponse("매장 정보를 정리했어요."));

		var answer = client().generateAnswer(storeQuestion());

		assertThat(answer.answer()).isEqualTo("매장 정보를 정리했어요.");
		verify(locationService, times(3)).search("강남역");
		assertThat(server.requests()).hasSize(5);
	}

	@Test
	void mapsServerFailureWhileCallingTools() {
		server.respond(toolCallResponse("findNearbyStores", Map.of("place", "강남역")));
		server.respond(503, "{\"error\":\"unavailable\"}");

		assertFailure(client(), storeQuestion(), LlmErrorCode.LLM_SERVICE_UNAVAILABLE);
		verify(locationService).search("강남역");
	}

	@ParameterizedTest
	@ValueSource(strings = {"", " \n "})
	void rejectsEmptyAnswer(String content) {
		server.respond(answerResponse(content));

		assertFailure(client(), question("질문"), LlmErrorCode.LLM_RESPONSE_INVALID);
	}

	@Test
	void rejectsMalformedResponse() {
		server.respond("not-json");

		assertFailure(client(), question("질문"), LlmErrorCode.LLM_RESPONSE_INVALID);
	}

	@Test
	void rejectsAnswerTruncatedByLength() {
		server.respond(truncatedResponse("미완성 답변"));

		assertFailure(client(), question("질문"), LlmErrorCode.LLM_RESPONSE_INVALID);
	}

	@Test
	void mapsReadTimeout() {
		server.hang();

		var client = createClient(server.url(), MODEL_NAME, Duration.ofMillis(150));

		assertFailure(client, question("질문"), LlmErrorCode.LLM_TIMEOUT);
	}

	@Test
	void mapsRefusedConnection() {
		var client = createClient("http://127.0.0.1:1", MODEL_NAME, Duration.ofSeconds(3));

		assertFailure(client, question("질문"), LlmErrorCode.LLM_SERVICE_UNAVAILABLE);
	}

	@Test
	void mapsServerErrorWithoutRetrying() {
		server.respond(503, "{\"error\":\"unavailable\"}");

		assertFailure(client(), question("질문"), LlmErrorCode.LLM_SERVICE_UNAVAILABLE);
		assertThat(server.requests()).hasSize(1);
	}

	@Test
	void refusesMissingModelWithoutCallingServer() {
		var client = createClient(server.url(), " ", Duration.ofSeconds(3));

		assertFailure(client, question("질문"), LlmErrorCode.LLM_MODEL_NOT_CONFIGURED);
		assertThat(server.requests()).isEmpty();
	}

	private LlmClient client() {
		return createClient(server.url(), MODEL_NAME, Duration.ofSeconds(3));
	}

	private LlmRequestDto question(String content) {
		return new LlmRequestDto(List.of(new LlmMessageRequestDto(LlmMessageRole.USER, content)));
	}

	private LlmRequestDto storeQuestion() {
		var registry = new AiToolRegistry(
				new StoreTools(new NearbyStoreSearcher(storeService, 3.0, 5), locationService));
		return new LlmRequestDto(List.of(
				new LlmMessageRequestDto(LlmMessageRole.SYSTEM, "시스템"),
				new LlmMessageRequestDto(LlmMessageRole.USER, STORE_QUESTION)))
				.withTools(registry.resolve(Set.of(AiTool.STORE_SEARCH)), Map.of(
						StoreTools.QUESTION, STORE_QUESTION,
						StoreTools.RECORDER, recorder));
	}

	private void assertFailure(LlmClient client, LlmRequestDto request, LlmErrorCode expected) {
		assertThatThrownBy(() -> client.generateAnswer(request))
				.isInstanceOfSatisfying(LlmException.class,
						exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
	}
}
