package com.ubot.guardreview.service;

import static com.ubot.guardreview.exception.ReviewErrorCode.CONFLICT;
import static com.ubot.guardreview.exception.ReviewErrorCode.WARNING_ONLY;
import static com.ubot.guardreview.exception.ReviewErrorCode.ALREADY_REGISTERED;
import static com.ubot.guardreview.exception.ReviewErrorCode.CATALOG_CHANGED;
import static com.ubot.guardreview.model.ReviewModels.Kind;
import static com.ubot.guardreview.model.ReviewModels.Candidate;

import com.ubot.forbiddenword.entity.ForbiddenWord;
import com.ubot.forbiddenword.enums.ForbiddenWordStatus;
import com.ubot.forbiddenword.repository.ForbiddenWordRepository;
import com.ubot.guardreview.exception.ReviewException;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 기존 금칙어는 기존 화면에서 관리합니다. 후보에는 등록 소유권을 자동으로 넘기지 않습니다. */
@Service
@RequiredArgsConstructor
public class ReviewCatalogService {
	private final ForbiddenWordRepository words;

	public boolean isRegistered(String expression) {
		return words.existsByWord(expression.strip());
	}

	public List<Candidate> display(List<Candidate> candidates) {
		if (candidates.stream().noneMatch(c -> c.kind() == Kind.WORD))
			return candidates;
		List<ForbiddenWord> catalog = words.findAll();
		return candidates.stream().map(c -> {
			if (c.kind() != Kind.WORD)
				return c;
			ForbiddenWord linked = catalog.stream().filter(w -> Objects.equals(w.getId(), c.wordId())).findFirst()
					.orElse(null);
			String status = c.status();
			if (owns(c, linked)) {
				if (linked.getStatus() == ForbiddenWordStatus.ACTIVE)
					status = "APPLIED";
				else if ("APPLIED".equals(status))
					status = "DISABLED";
			} else if (catalog.stream().anyMatch(w -> w.getWord().equals(c.expression())))
				status = "REGISTERED";
			else if (c.wordId() != null || "APPLIED".equals(status))
				status = "CATALOG_CHANGED";
			return new Candidate(c.id(), c.kind(), c.category(), c.expression(), c.reason(), c.intent(), c.revision(),
					status, c.wordId(), c.profile(), c.vector(), c.occurrences(), c.unreadOccurrences());
		}).toList();
	}

	/** 단어가 수동 수정·삭제되면 예전 연결을 끊습니다. 이전 단어를 수정하거나 비활성화하지 않습니다. */
	public Long linkForEdit(Candidate c, String expression) {
		ForbiddenWord linked = linked(c);
		if (owns(c, linked) && linked.getStatus() == ForbiddenWordStatus.ACTIVE)
			throw new ReviewException(CONFLICT);
		return owns(c, linked) && c.expression().equals(expression) ? c.wordId() : null;
	}

	public void requireSelectable(Candidate c) {
		if (c.kind() != Kind.WORD)
			throw new ReviewException(WARNING_ONLY);
		if (!"PENDING".equals(c.status()))
			throw new ReviewException(CONFLICT);
		ForbiddenWord linked = linked(c);
		if (c.wordId() != null && !owns(c, linked))
			throw new ReviewException(CATALOG_CHANGED);
		if (owns(c, linked) && linked.getStatus() == ForbiddenWordStatus.INACTIVE)
			return;
		if (isRegistered(c.expression()))
			throw new ReviewException(ALREADY_REGISTERED);
	}

	public ForbiddenWord requireOwnedActive(Candidate c) {
		ForbiddenWord linked = linked(c);
		if (c.kind() != Kind.WORD || !owns(c, linked) || linked.getStatus() != ForbiddenWordStatus.ACTIVE)
			throw new ReviewException(CATALOG_CHANGED);
		return linked;
	}

	private ForbiddenWord linked(Candidate c) {
		return c.wordId() == null ? null : words.findById(c.wordId()).orElse(null);
	}

	private boolean owns(Candidate c, ForbiddenWord linked) {
		return c.wordId() != null && linked != null && c.expression().equals(linked.getWord());
	}
}
