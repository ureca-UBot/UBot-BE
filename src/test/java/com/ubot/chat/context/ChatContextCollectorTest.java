package com.ubot.chat.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.ContextSection;
import com.ubot.ai.dto.SubQuestionFaqs;
import com.ubot.ai.tool.AiTool;
import com.ubot.ai.tool.NearbyStoreSearcher;
import com.ubot.ai.tool.product.ProductNameIndex;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ChatContextCollectorTest {
	private final ChatContext context = new ChatContext(1L, "질문", null);
	private final FaqSearchResponseDto general1 = faq(1L, Intent.GENERAL);
	private final FaqSearchResponseDto store = faq(2L, Intent.STORE_DATA);
	private final FaqSearchResponseDto general2 = faq(3L, Intent.GENERAL);

	@Test
	void 검색된_intent마다_핸들러를_한_번씩_실행한다() {
		var general = new RecordingHandler(Intent.GENERAL);
		var storeHandler = new RecordingHandler(Intent.STORE_DATA);
		var user = new RecordingHandler(Intent.USER_DATA);
		var collector = new ChatContextCollector(List.of(general, storeHandler, user, new RecordingHandler(Intent.PRODUCT_DATA)));

		AnswerMaterials materials = collector.collect(context, List.of(general1, store, general2));

		// 같은 intent가 두 개 걸려도 핸들러는 유사도 순서대로 두 결과를 한 번에 받습니다.
		assertThat(general.calls).containsExactly(List.of(general1, general2));
		assertThat(storeHandler.calls).containsExactly(List.of(store));
		assertThat(user.calls).isEmpty();
		assertThat(materials.question()).isEqualTo("질문");
		assertThat(materials.userId()).isEqualTo(1L);
		assertThat(materials.sections()).extracting(ContextSection::title).containsExactly("GENERAL", "STORE_DATA");
	}

	@Test
	void 실제_핸들러로_FAQ와_도구를_모은다() {
		var collector = new ChatContextCollector(List.of(
				new GeneralIntentHandler(), new StoreIntentHandler(mock(NearbyStoreSearcher.class)), new UserIntentHandler(null),
				new ProductIntentHandler(mock(ProductNameIndex.class))));

		AnswerMaterials materials = collector.collect(context, List.of(general1, store));

		assertThat(materials.faqs()).containsExactly(general1);
		assertThat(materials.tools()).containsExactly(AiTool.STORE_SEARCH);
		assertThat(materials.sections()).isEmpty();
	}

	@Test
	void 하위_질문의_FAQ를_합쳐_intent마다_한_번만_핸들러를_실행하고_핸들러가_넣은_FAQ만_질문별로_다시_묶는다() {
		var general = new RecordingHandler(Intent.GENERAL);
		var storeHandler = new RecordingHandler(Intent.STORE_DATA);
		var collector = new ChatContextCollector(List.of(general, storeHandler, new RecordingHandler(Intent.USER_DATA),
				new RecordingHandler(Intent.PRODUCT_DATA)));
		var forwarding = new ChatContextCollector(List.of(
				new GeneralIntentHandler(), new StoreIntentHandler(mock(NearbyStoreSearcher.class)), new UserIntentHandler(null),
				new ProductIntentHandler(mock(ProductNameIndex.class))));
		var subQuestions = List.of(
				new SubQuestionFaqs("유심 재발급 방법", List.of(general1, store), "유심 재발급 방법"),
				new SubQuestionFaqs("유심 재발급 비용", List.of(general1, general2), "유심 재발급 비용"),
				new SubQuestionFaqs("해외 로밍 요금", List.of(), "해외 로밍 요금"));

		collector.collectBySubQuestion(context, subQuestions);
		AnswerMaterials materials = forwarding.collectBySubQuestion(context, subQuestions);

		// 두 하위 질문에서 같은 FAQ(1번)가 걸려도 핸들러는 한 번, 중복 없이 받습니다.
		assertThat(general.calls).containsExactly(List.of(general1, general2));
		assertThat(storeHandler.calls).containsExactly(List.of(store));
		// 매장 FAQ는 핸들러가 참고 자료로 넣지 않으므로 하위 질문 묶음에서도 빠집니다. 근거 없는 하위 질문은 빈 묶음으로 남습니다.
		assertThat(materials.faqs()).containsExactly(general1, general2);
		assertThat(materials.subQuestions()).containsExactly(
				new SubQuestionFaqs("유심 재발급 방법", List.of(general1), "유심 재발급 방법"),
				new SubQuestionFaqs("유심 재발급 비용", List.of(general1, general2), "유심 재발급 비용"),
				new SubQuestionFaqs("해외 로밍 요금", List.of(), "해외 로밍 요금"));
	}

	@Test
	void 핸들러가_빠진_intent가_있으면_생성할_수_없다() {
		assertThatThrownBy(() -> new ChatContextCollector(List.of(
				new RecordingHandler(Intent.GENERAL), new RecordingHandler(Intent.STORE_DATA))))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("USER_DATA");
	}

	@Test
	void 같은_intent의_핸들러가_중복되면_생성할_수_없다() {
		assertThatThrownBy(() -> new ChatContextCollector(List.of(
				new RecordingHandler(Intent.GENERAL), new RecordingHandler(Intent.GENERAL),
				new RecordingHandler(Intent.STORE_DATA), new RecordingHandler(Intent.USER_DATA),
				new RecordingHandler(Intent.PRODUCT_DATA))))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("GENERAL");
	}

	@Test
	void 일반_FAQ만_걸려도_본인_정보를_묻는_질문이면_사용자_정보_핸들러를_실행한다() {
		var general = new RecordingHandler(Intent.GENERAL);
		var user = new RecordingHandler(Intent.USER_DATA);
		var collector = new ChatContextCollector(List.of(general, user, new RecordingHandler(Intent.STORE_DATA),
				new RecordingHandler(Intent.PRODUCT_DATA)));

		collector.collect(new ChatContext(1L, "내 데이터 사용량 알려줘", null), List.of(general1));
		collector.collect(new ChatContext(1L, "데이터 사용량 확인 방법 알려줘", null), List.of(general1));

		// 사용자 정보 핸들러는 FAQ를 쓰지 않아 빈 목록으로 한 번만 실행됩니다. 본인을 가리키지 않은 질문에는 실행하지 않습니다.
		assertThat(user.calls).containsExactly(List.of());
		assertThat(general.calls).hasSize(2);
	}

	@Test
	void 상품_FAQ가_1위이고_본인을_가리키지_않으면_함께_걸린_사용자_정보_FAQ를_쓰지_않는다() {
		var user = new RecordingHandler(Intent.USER_DATA);
		var product = new RecordingHandler(Intent.PRODUCT_DATA);
		var collector = new ChatContextCollector(List.of(user, product, new RecordingHandler(Intent.GENERAL),
				new RecordingHandler(Intent.STORE_DATA)));
		var productFaq = faq(4L, Intent.PRODUCT_DATA);
		var userFaq = faq(5L, Intent.USER_DATA);

		collector.collect(new ChatContext(1L, "요금제 정보 알려줘", null), List.of(productFaq, userFaq));

		assertThat(product.calls).containsExactly(List.of(productFaq));
		assertThat(user.calls).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = {"내 요금제 정보 알려줘", "나 무슨 요금제 써?", "나한테 맞는 요금제 추천해줘"})
	void 본인을_가리키면_상품_FAQ가_1위여도_사용자_정보_FAQ를_쓴다(String question) {
		var user = new RecordingHandler(Intent.USER_DATA);
		var collector = new ChatContextCollector(List.of(user, new RecordingHandler(Intent.PRODUCT_DATA),
				new RecordingHandler(Intent.GENERAL), new RecordingHandler(Intent.STORE_DATA)));
		var userFaq = faq(5L, Intent.USER_DATA);

		collector.collect(new ChatContext(1L, question, null), List.of(faq(4L, Intent.PRODUCT_DATA), userFaq));

		assertThat(user.calls).containsExactly(List.of(userFaq));
	}

	@Test
	void 사용자_정보_FAQ가_1위면_본인을_가리키지_않아도_사용자_정보_FAQ를_쓴다() {
		var user = new RecordingHandler(Intent.USER_DATA);
		var collector = new ChatContextCollector(List.of(user, new RecordingHandler(Intent.PRODUCT_DATA),
				new RecordingHandler(Intent.GENERAL), new RecordingHandler(Intent.STORE_DATA)));
		var userFaq = faq(5L, Intent.USER_DATA);

		collector.collect(new ChatContext(1L, "요금제 이름 알려줘", null), List.of(userFaq, faq(4L, Intent.PRODUCT_DATA)));

		assertThat(user.calls).containsExactly(List.of(userFaq));
	}

	@ParameterizedTest
	@ValueSource(strings = {"요금제 추천해줘", "저렴한 요금제 뭐 있어?", "제일 싼 요금제", "나라별 로밍 요금"})
	void 단어_안의_글자는_본인_지칭으로_보지_않는다(String question) {
		assertThat(UserIntentHandler.mentionsSelf(question)).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = {"내 데이터 사용량 알려줘", "제 요금제 뭐예요", "내가 이번 달 요금 얼마 냈어?", "나의 약정 언제 끝나?"})
	void 자기_지칭과_회원_기록_항목이_함께_있으면_본인_정보_질문으로_본다(String question) {
		assertThat(UserIntentHandler.asksOwnData(question)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {"제일 싼 요금제 알려줘", "데이터 제공량 많은 요금제", "약정 해제하면 위약금 있어?", "내 근처 매장 알려줘"})
	void 자기_지칭이나_회원_기록_항목이_없으면_본인_정보_질문으로_보지_않는다(String question) {
		assertThat(UserIntentHandler.asksOwnData(question)).isFalse();
	}

	private static FaqSearchResponseDto faq(Long id, Intent intent) {
		return new FaqSearchResponseDto(id, "질문" + id, "답변" + id, 0.9, intent);
	}

	private static final class RecordingHandler implements IntentHandler {
		private final Intent intent;
		private final List<List<FaqSearchResponseDto>> calls = new ArrayList<>();

		private RecordingHandler(Intent intent) {
			this.intent = intent;
		}

		@Override
		public Intent intent() {
			return intent;
		}

		@Override
		public void contribute(ChatContext context, List<FaqSearchResponseDto> results, AnswerMaterials.Builder materials) {
			calls.add(results);
			materials.addSection(new ContextSection(intent.name(), "호출됨"));
		}
	}
}
