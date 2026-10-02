package com.ubot.chat.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.ContextSection;
import com.ubot.ai.dto.Location;
import com.ubot.ai.tool.AiTool;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatContextCollectorTest {
	private final ChatContext context = new ChatContext(1L, "질문", new Location(37.5, 127.0));
	private final FaqSearchResponseDto general1 = faq(1L, Intent.GENERAL);
	private final FaqSearchResponseDto store = faq(2L, Intent.STORE_DATA);
	private final FaqSearchResponseDto general2 = faq(3L, Intent.GENERAL);

	@Test
	void 검색된_intent마다_핸들러를_한_번씩_실행한다() {
		var general = new RecordingHandler(Intent.GENERAL);
		var storeHandler = new RecordingHandler(Intent.STORE_DATA);
		var user = new RecordingHandler(Intent.USER_DATA);
		var collector = new ChatContextCollector(List.of(general, storeHandler, user));

		AnswerMaterials materials = collector.collect(context, List.of(general1, store, general2));

		// 같은 intent가 두 개 걸려도 핸들러는 유사도 순서대로 두 결과를 한 번에 받습니다.
		assertThat(general.calls).containsExactly(List.of(general1, general2));
		assertThat(storeHandler.calls).containsExactly(List.of(store));
		assertThat(user.calls).isEmpty();
		assertThat(materials.question()).isEqualTo("질문");
		assertThat(materials.location()).isEqualTo(new Location(37.5, 127.0));
		assertThat(materials.sections()).extracting(ContextSection::title).containsExactly("GENERAL", "STORE_DATA");
	}

	@Test
	void 실제_핸들러로_FAQ와_도구를_모은다() {
		var collector = new ChatContextCollector(List.of(
				new GeneralIntentHandler(), new StoreIntentHandler(), new UserIntentHandler(null)));

		AnswerMaterials materials = collector.collect(context, List.of(general1, store));

		assertThat(materials.faqs()).containsExactly(general1);
		assertThat(materials.tools()).containsExactly(AiTool.STORE_SEARCH);
		assertThat(materials.sections()).isEmpty();
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
				new RecordingHandler(Intent.STORE_DATA), new RecordingHandler(Intent.USER_DATA))))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("GENERAL");
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
