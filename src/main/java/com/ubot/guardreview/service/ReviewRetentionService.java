package com.ubot.guardreview.service;

import com.ubot.guardreview.repository.ReviewRepository;
import java.util.function.IntSupplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 승인·수동 변경·작업 완료와 직렬화하고, 성공 지문과 경고의 중복 판정 연결을 보존합니다. */
@Service
@RequiredArgsConstructor
public class ReviewRetentionService {
	static final int BATCH_SIZE = 1000;
	static final int MAX_BATCHES = 20;
	private final ReviewRepository repository;

	@Transactional
	public void cleanup() {
		repository.catalogLock();
		repository.analysisLock();
		bounded(() -> repository.expireRunDetails(BATCH_SIZE));
		bounded(() -> repository.clearSourceSnapshots(BATCH_SIZE));
		bounded(() -> repository.pruneItems(BATCH_SIZE));
		bounded(() -> repository.pruneRuns(BATCH_SIZE));
		bounded(() -> repository.pruneHistory(BATCH_SIZE));
	}
	private void bounded(IntSupplier operation) {
		for (int batch = 0; batch < MAX_BATCHES; batch++)
			if (operation.getAsInt() < BATCH_SIZE)
				return;
	}
}
