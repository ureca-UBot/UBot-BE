package com.ubot.forbiddenword;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.common.GlobalException;
import com.ubot.common.PageResponseDto;
import com.ubot.forbiddenword.dto.request.ForbiddenWordCreateRequestDto;
import com.ubot.forbiddenword.dto.request.ForbiddenWordStatusUpdateRequestDto;
import com.ubot.forbiddenword.dto.request.ForbiddenWordUpdateRequestDto;
import com.ubot.forbiddenword.dto.response.ForbiddenWordResponseDto;
import com.ubot.forbiddenword.entity.ForbiddenWord;
import com.ubot.forbiddenword.enums.ForbiddenWordStatus;
import com.ubot.forbiddenword.exception.ForbiddenWordErrorCode;
import com.ubot.forbiddenword.exception.ForbiddenWordException;
import com.ubot.forbiddenword.repository.ForbiddenWordRepository;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import com.ubot.forbiddenword.service.ForbiddenWordService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// 캐시 갱신이 커밋 이후에 일어나는지 확인해야 하므로 테스트 트랜잭션으로 감싸지 않고 매 테스트 전에 테이블을 비웁니다.
@SpringBootTest
@ActiveProfiles("test")
@Import(PgvectorTestConfiguration.class)
@DisplayName("금지어 통합 테스트")
class ForbiddenWordIntegrationTest {

	@Autowired
	private ForbiddenWordRepository repository;

	@Autowired
	private ForbiddenWordService service;

	@Autowired
	private ForbiddenWordFilterService filterService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@BeforeEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM forbidden_words");
		filterService.refreshForbiddenWordCache();
	}

	@Test
	@DisplayName("엔티티가 forbidden_words 테이블의 컬럼과 상태 문자열에 매핑된다")
	void mapsEntityToTable() {
		LocalDateTime now = LocalDateTime.now();
		ForbiddenWord saved = repository.saveAndFlush(ForbiddenWord.builder()
				.word("바보").status(ForbiddenWordStatus.INACTIVE).createdAt(now).updatedAt(now).build());

		assertThat(saved.getId()).isNotNull();
		assertThat(jdbcTemplate.queryForObject(
				"SELECT status FROM forbidden_words WHERE id = ?", String.class, saved.getId())).isEqualTo("INACTIVE");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT word FROM forbidden_words WHERE id = ?", String.class, saved.getId())).isEqualTo("바보");
		assertThat(repository.findById(saved.getId()).orElseThrow().getStatus()).isEqualTo(ForbiddenWordStatus.INACTIVE);
	}

	@Test
	@DisplayName("word UNIQUE 제약 때문에 같은 단어를 두 번 저장할 수 없다")
	void rejectsDuplicateWordAtDatabaseLevel() {
		save("바보", ForbiddenWordStatus.ACTIVE);

		assertThatThrownBy(() -> save("바보", ForbiddenWordStatus.INACTIVE))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("findAllByStatus는 해당 상태의 금지어만 조회한다")
	void findsAllByStatus() {
		save("바보", ForbiddenWordStatus.ACTIVE);
		save("멍청이", ForbiddenWordStatus.INACTIVE);
		save("욕설", ForbiddenWordStatus.ACTIVE);

		assertThat(repository.findAllByStatus(ForbiddenWordStatus.ACTIVE))
				.extracting(ForbiddenWord::getWord).containsExactlyInAnyOrder("바보", "욕설");
		assertThat(repository.findAllByStatus(ForbiddenWordStatus.INACTIVE))
				.extracting(ForbiddenWord::getWord).containsExactly("멍청이");
	}

	@Test
	@DisplayName("existsByWord와 existsByWordAndIdNot이 자기 자신을 제외하고 중복을 확인한다")
	void checksDuplicates() {
		ForbiddenWord first = save("바보", ForbiddenWordStatus.ACTIVE);
		ForbiddenWord second = save("멍청이", ForbiddenWordStatus.ACTIVE);

		assertThat(repository.existsByWord("바보")).isTrue();
		assertThat(repository.existsByWord("없는단어")).isFalse();
		assertThat(repository.existsByWordAndIdNot("바보", first.getId())).isFalse();
		assertThat(repository.existsByWordAndIdNot("바보", second.getId())).isTrue();
	}

	@Test
	@DisplayName("금지어를 생성하면 커밋 후 캐시에 반영되어 질문이 차단된다")
	void createRefreshesCache() {
		assertThatCode(() -> filterService.validateForbiddenWord("너 바보야")).doesNotThrowAnyException();

		ForbiddenWordResponseDto created = service.createForbiddenWord(new ForbiddenWordCreateRequestDto(" 바보 "));

		assertThat(created.word()).isEqualTo("바보");
		assertThat(created.status()).isEqualTo(ForbiddenWordStatus.ACTIVE);
		assertThatThrownBy(() -> filterService.validateForbiddenWord("너 바보야"))
				.isInstanceOf(ForbiddenWordException.class);
	}

	@Test
	@DisplayName("금지어를 INACTIVE로 수정하면 캐시에서 제외되고, 다시 ACTIVE로 수정하면 복원된다")
	void statusChangeRefreshesCache() {
		Long id = service.createForbiddenWord(new ForbiddenWordCreateRequestDto("바보")).id();

		service.updateForbiddenWordStatus(id, new ForbiddenWordStatusUpdateRequestDto(ForbiddenWordStatus.INACTIVE));
		assertThatCode(() -> filterService.validateForbiddenWord("너 바보야")).doesNotThrowAnyException();
		assertThat(jdbcTemplate.queryForObject("SELECT status FROM forbidden_words WHERE id = ?", String.class, id))
				.isEqualTo("INACTIVE");

		service.updateForbiddenWordStatus(id, new ForbiddenWordStatusUpdateRequestDto(ForbiddenWordStatus.ACTIVE));
		assertThatThrownBy(() -> filterService.validateForbiddenWord("너 바보야"))
				.isInstanceOf(ForbiddenWordException.class);
	}

	@Test
	@DisplayName("INACTIVE 금지어의 단어를 수정해도 상태는 INACTIVE로 유지되어 검사에 걸리지 않는다")
	void wordChangeKeepsInactiveStatus() {
		Long id = service.createForbiddenWord(new ForbiddenWordCreateRequestDto("바보")).id();
		service.updateForbiddenWordStatus(id, new ForbiddenWordStatusUpdateRequestDto(ForbiddenWordStatus.INACTIVE));

		ForbiddenWordResponseDto updated = service.updateForbiddenWord(id, new ForbiddenWordUpdateRequestDto("멍청이"));

		assertThat(updated.status()).isEqualTo(ForbiddenWordStatus.INACTIVE);
		assertThatCode(() -> filterService.validateForbiddenWord("너 멍청이야")).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("금지어 문자열을 수정하면 이전 단어는 통과하고 새 단어는 차단한다")
	void wordChangeRefreshesCache() {
		Long id = service.createForbiddenWord(new ForbiddenWordCreateRequestDto("바보")).id();

		ForbiddenWordResponseDto updated = service.updateForbiddenWord(id, new ForbiddenWordUpdateRequestDto("멍청이"));

		assertThat(updated.word()).isEqualTo("멍청이");
		assertThatCode(() -> filterService.validateForbiddenWord("너 바보야")).doesNotThrowAnyException();
		assertThatThrownBy(() -> filterService.validateForbiddenWord("너 멍청이야"))
				.isInstanceOf(ForbiddenWordException.class);
		assertThat(jdbcTemplate.queryForObject("SELECT word FROM forbidden_words WHERE id = ?", String.class, id))
				.isEqualTo("멍청이");
	}

	@Test
	@DisplayName("두 관리자가 같은 금지어의 단어와 상태를 동시에 바꾸면 두 변경이 모두 남는다")
	void concurrentWordAndStatusUpdatesDoNotOverwriteEachOther() {
		Long id = save("바보", ForbiddenWordStatus.ACTIVE).getId();

		// 두 관리자가 같은 원본을 읽은 뒤 A(단어 수정)가 먼저, B(상태 변경)가 나중에 커밋합니다.
		EntityManager adminA = entityManagerFactory.createEntityManager();
		EntityManager adminB = entityManagerFactory.createEntityManager();
		try {
			adminA.getTransaction().begin();
			adminB.getTransaction().begin();
			ForbiddenWord seenByA = adminA.find(ForbiddenWord.class, id);
			ForbiddenWord seenByB = adminB.find(ForbiddenWord.class, id);

			seenByA.updateWord("멍청이");
			adminA.getTransaction().commit();
			seenByB.updateStatus(ForbiddenWordStatus.INACTIVE);
			adminB.getTransaction().commit();
		} finally {
			adminA.close();
			adminB.close();
		}

		assertThat(jdbcTemplate.queryForMap("SELECT word, status FROM forbidden_words WHERE id = ?", id))
				.containsEntry("word", "멍청이")
				.containsEntry("status", "INACTIVE");
	}

	@Test
	@DisplayName("한 관리자가 금지어를 수정하는 동안 다른 관리자의 수정은 커밋될 때까지 기다린다")
	void pessimisticLockMakesSecondUpdateWait() throws Exception {
		Long id = save("바보", ForbiddenWordStatus.ACTIVE).getId();
		CountDownLatch lockAcquired = new CountDownLatch(1);
		CountDownLatch releaseLock = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			// 관리자 A: 행을 잠근 채 트랜잭션을 유지합니다.
			Future<?> adminA = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
				repository.findByIdForUpdate(id).orElseThrow().updateWord("멍청이");
				lockAcquired.countDown();
				awaitQuietly(releaseLock);
			}));
			assertThat(lockAcquired.await(10, TimeUnit.SECONDS)).isTrue();

			// 관리자 B: 같은 행의 상태를 바꾸려 하지만 A가 커밋할 때까지 대기합니다.
			Future<ForbiddenWordResponseDto> adminB = executor.submit(() -> service.updateForbiddenWordStatus(id,
					new ForbiddenWordStatusUpdateRequestDto(ForbiddenWordStatus.INACTIVE)));
			assertThatThrownBy(() -> adminB.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);

			releaseLock.countDown();
			adminA.get(10, TimeUnit.SECONDS);
			ForbiddenWordResponseDto result = adminB.get(10, TimeUnit.SECONDS);

			// B는 A가 커밋한 최신 단어를 읽은 뒤 상태만 바꿉니다.
			assertThat(result.word()).isEqualTo("멍청이");
			assertThat(result.status()).isEqualTo(ForbiddenWordStatus.INACTIVE);
		} finally {
			releaseLock.countDown();
			executor.shutdownNow();
		}
	}

	private static void awaitQuietly(CountDownLatch latch) {
		try {
			latch.await(10, TimeUnit.SECONDS);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
	}

	@Test
	@DisplayName("트랜잭션이 롤백되면 캐시는 변경되지 않는다")
	void rollbackDoesNotRefreshCache() {
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			service.createForbiddenWord(new ForbiddenWordCreateRequestDto("바보"));
			status.setRollbackOnly();
		});

		assertThat(repository.existsByWord("바보")).isFalse();
		assertThatCode(() -> filterService.validateForbiddenWord("너 바보야")).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("중복 단어로 생성하거나 수정하면 예외가 발생하고 DB와 캐시는 그대로다")
	void duplicateDoesNotChangeState() {
		service.createForbiddenWord(new ForbiddenWordCreateRequestDto("바보"));
		Long otherId = service.createForbiddenWord(new ForbiddenWordCreateRequestDto("멍청이")).id();

		assertThatThrownBy(() -> service.createForbiddenWord(new ForbiddenWordCreateRequestDto("바보")))
				.isInstanceOfSatisfying(ForbiddenWordException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(ForbiddenWordErrorCode.FORBIDDEN_WORD_EXIST));
		assertThatThrownBy(() -> service.updateForbiddenWord(otherId, new ForbiddenWordUpdateRequestDto("바보")))
				.isInstanceOf(ForbiddenWordException.class);

		assertThat(repository.count()).isEqualTo(2);
		assertThatThrownBy(() -> filterService.validateForbiddenWord("멍청이")).isInstanceOf(ForbiddenWordException.class);
	}

	@Test
	@DisplayName("서버 시작 시 초기화 메서드는 DB의 ACTIVE 금지어만 캐시에 적재한다")
	void initializeLoadsOnlyActiveWords() {
		save("바보", ForbiddenWordStatus.ACTIVE);
		save("멍청이", ForbiddenWordStatus.INACTIVE);

		filterService.initializeForbiddenWordCache();

		assertThatThrownBy(() -> filterService.validateForbiddenWord("바보")).isInstanceOf(ForbiddenWordException.class);
		assertThatCode(() -> filterService.validateForbiddenWord("멍청이")).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("목록은 ACTIVE와 INACTIVE를 모두 id 내림차순으로 페이지 단위 조회한다")
	void listsAllWithPaging() {
		for (int i = 1; i <= 12; i++) {
			save("단어" + i, i % 2 == 0 ? ForbiddenWordStatus.INACTIVE : ForbiddenWordStatus.ACTIVE);
		}

		PageResponseDto<ForbiddenWordResponseDto> first = service.getForbiddenWordList(0, 10);
		PageResponseDto<ForbiddenWordResponseDto> second = service.getForbiddenWordList(1, 10);

		assertThat(first.totalElements()).isEqualTo(12);
		assertThat(first.totalPages()).isEqualTo(2);
		assertThat(first.content()).hasSize(10);
		assertThat(second.content()).hasSize(2);
		List<Long> ids = first.content().stream().map(ForbiddenWordResponseDto::id).toList();
		assertThat(ids).isSortedAccordingTo(java.util.Comparator.reverseOrder());
		assertThat(first.content()).extracting(ForbiddenWordResponseDto::status)
				.contains(ForbiddenWordStatus.ACTIVE, ForbiddenWordStatus.INACTIVE);
		assertThatThrownBy(() -> service.getForbiddenWordList(0, 30)).isInstanceOf(GlobalException.class);
	}

	private ForbiddenWord save(String word, ForbiddenWordStatus status) {
		LocalDateTime now = LocalDateTime.now();
		return repository.saveAndFlush(ForbiddenWord.builder()
				.word(word).status(status).createdAt(now).updatedAt(now).build());
	}
}
