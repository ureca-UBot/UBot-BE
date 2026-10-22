package com.ubot.guardreview.service;

import com.ubot.guardreview.exception.ReviewErrorCode;
import static com.ubot.guardreview.model.ReviewModels.Kind;
import static com.ubot.guardreview.model.ReviewModels.Stage;
import static com.ubot.guardreview.model.ReviewModels.Candidate;
import static com.ubot.guardreview.model.ReviewModels.GoldenCase;
import static com.ubot.guardreview.model.ReviewModels.CaseResult;
import static com.ubot.guardreview.model.ReviewModels.Validation;

import com.ubot.ai.service.AiOutputGuardService;
import com.ubot.chat.service.ChatInputGuardService;
import com.ubot.chat.service.ChatPromptInjectionService;
import com.ubot.common.GlobalException;
import com.ubot.forbiddenword.enums.ForbiddenWordStatus;
import com.ubot.forbiddenword.repository.ForbiddenWordRepository;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import com.ubot.guardreview.exception.ReviewException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** LLM·Judge·FAQ 조회 없이 기존 검사와 확인된 문구 보존 기준을 비교합니다. */
@Service
@RequiredArgsConstructor
public class ReviewValidationService {
	private final ForbiddenWordRepository words;
	private final ForbiddenWordFilterService filter;
	private final ChatPromptInjectionService promptGuard;
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String DATASET = "guard-review/golden-cases.json";

	public Set<String> currentWords() {
		return words.findAllByStatus(ForbiddenWordStatus.ACTIVE).stream()
				.map(com.ubot.forbiddenword.entity.ForbiddenWord::getWord)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
	}
	public String baseVersion() {
		return baseVersion(currentWords());
	}
	public String baseVersion(Set<String> snapshot) {
		return hash(String.join("\n", new TreeSet<>(snapshot)) + engineVersion());
	}
	public String datasetVersion() {
		return hash(new String(datasetBytes(), StandardCharsets.UTF_8));
	}
	public List<GoldenCase> dataset() {
		return List.of(JSON.readValue(datasetBytes(), GoldenCase[].class));
	}
	private byte[] datasetBytes() {
		try (var input = new ClassPathResource(DATASET).getInputStream()) {
			return input.readAllBytes();
		} catch (IOException e) {
			throw new IllegalStateException("검증 자료를 읽지 못했습니다.", e);
		}
	}
	private String engineVersion() {
		StringBuilder result = new StringBuilder();
		for (Class<?> type : List.of(ForbiddenWordFilterService.class, ChatInputGuardService.class,
				ChatPromptInjectionService.class, AiOutputGuardService.class, ReviewValidationService.class)) {
			try (var in = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
				if (in == null)
					throw new IllegalStateException("검사 코드 버전을 읽지 못했습니다.");
				result.append(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes())));
			} catch (java.security.NoSuchAlgorithmException | IOException e) {
				throw new IllegalStateException(e);
			}
		}
		return result.toString();
	}
	public Validation validate(Collection<Candidate> candidates, Set<String> active, List<GoldenCase> cases,
			String datasetVersion) {
		if (candidates.isEmpty() || candidates.stream().anyMatch(c -> c.kind() != Kind.WORD))
			throw new ReviewException(ReviewErrorCode.WARNING_ONLY);
		Set<String> proposed = new TreeSet<>(active);
		candidates.forEach(c -> proposed.add(c.expression()));
		var before = filter.preview(active);
		var after = filter.preview(proposed);
		List<CaseResult> results = new ArrayList<>();
		for (GoldenCase c : cases) {
			String original = run(c.stage(), c.text(), before);
			String candidate = run(c.stage(), c.text(), after);
			boolean baselineFailure = c.preserve().stream().anyMatch(s -> !contains(original, s))
					|| c.remove().stream().anyMatch(s -> contains(original, s));
			boolean falsePositive = c.preserve().stream()
					.anyMatch(s -> contains(original, s) && !contains(candidate, s));
			boolean regression = c.remove().stream().anyMatch(s -> !contains(original, s) && contains(candidate, s));
			results.add(new CaseResult(c.id(), c.category(), c.stage(), original, candidate, falsePositive, regression,
					baselineFailure,
					c.remove().stream().anyMatch(s -> contains(original, s) && !contains(candidate, s))));
		}
		return new Validation(baseVersion(active), datasetVersion, List.copyOf(results),
				(int) results.stream().filter(CaseResult::falsePositive).count(),
				(int) results.stream().filter(CaseResult::regression).count(),
				(int) results.stream().filter(CaseResult::baselineFailure).count(),
				(int) results.stream().filter(CaseResult::newDetection).count());
	}
	private String run(Stage stage, String text, ForbiddenWordFilterService preview) {
		try {
			return switch (stage) {
				case INPUT -> new ChatInputGuardService(preview, promptGuard).prepareQuestion(text);
				case OUTPUT -> new AiOutputGuardService(preview).previewForbiddenWordStage(text);
				case COUNSEL -> {
					preview.validateForbiddenWord(text);
					yield text;
				}
			};
		} catch (GlobalException e) {
			String code = e.getErrorCode().getCode();
			if (Set.of("FW-003", "CHAT-022", "CHAT-023").contains(code))
				return "";
			throw e;
		}
	}
	private boolean contains(String text, String part) {
		return !part.isBlank() && text.replaceAll("\\s+", "").contains(part.replaceAll("\\s+", ""));
	}
	public static String hash(String value) {
		try {
			return HexFormat.of()
					.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
