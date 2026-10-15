package com.ubot.chat.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.RedisTestConfiguration;
import com.ubot.chat.dto.response.ChatStoreDto;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.job.dto.ChatJobResult;
import com.ubot.chat.job.dto.ChatRequestContext;
import com.ubot.chat.job.entity.ChatJob;
import com.ubot.chat.job.entity.ChatJobStatus;
import com.ubot.chat.job.repository.ChatJobQueueRepository;
import com.ubot.chat.job.repository.ChatJobRepository;
import com.ubot.chat.job.stream.ChatJobMessage;
import com.ubot.chat.job.stream.ChatJobResultStore;
import com.ubot.chat.job.stream.ChatJobStream;
import com.ubot.chat.job.stream.PendingChatJob;
import com.ubot.chat.job.stream.ReceivedChatJob;
import com.ubot.chat.repository.AnswerAttemptsHistoryRepository;
import com.ubot.conversation.service.ConversationService;
import com.ubot.ranking.enums.RegionSido;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands.XClaimOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@Import({PgvectorTestConfiguration.class, RedisTestConfiguration.class})
@ActiveProfiles("test")
@DisplayName("답변 작업 스트림·선점 통합 테스트")
class ChatJobStreamIntegrationTest {
	@Autowired ChatJobStream stream;
	@Autowired ChatJobResultStore results;
	@Autowired ChatJobQueueRepository queue;
	@Autowired ChatJobRepository jobs;
	@Autowired AnswerAttemptsHistoryRepository attempts;
	@Autowired ConversationService conversations;
	@Autowired StringRedisTemplate redis;
	@Autowired JdbcTemplate jdbc;

	@BeforeEach
	void cleanUp() {
		redis.delete(ChatJobStream.STREAM_KEY);
		redis.delete(redis.keys("chat:jobs:published:*"));
		attempts.deleteAllInBatch();
		jdbc.update("delete from conversations");
		stream.ensureGroup();
	}

	@Test
	@DisplayName("그룹 생성은 이미 있어도 그대로 쓴다")
	void ensureGroupIsIdempotent() {
		stream.ensureGroup();
		stream.ensureGroup();

		assertThat(redis.opsForStream().groups(ChatJobStream.STREAM_KEY))
				.singleElement().satisfies(group -> assertThat(group.groupName()).isEqualTo(ChatJobStream.GROUP));
	}

	@Test
	@DisplayName("발행한 메시지는 좌표·지역과 함께 읽히고, ACK하기 전까지 처리 대기로 남는다")
	void publishedMessageStaysPendingUntilAck() {
		var message = new ChatJobMessage(7L, new ChatRequestContext(37.5, 127.0, RegionSido.SEOUL));
		stream.publish(message);

		List<ReceivedChatJob> received = stream.read("worker-1", 10, null);

		assertThat(received).singleElement().satisfies(got -> assertThat(got.message()).isEqualTo(message));
		assertThat(pendingCount()).isEqualTo(1);
		stream.ack(received.getFirst().messageId());
		assertThat(pendingCount()).isZero();
		assertThat(stream.read("worker-1", 10, null)).isEmpty();
	}

	@Test
	@DisplayName("한 Worker가 받은 메시지는 다른 Worker에게 다시 전달되지 않는다")
	void messageIsDeliveredToOneConsumer() {
		stream.publish(new ChatJobMessage(1L, new ChatRequestContext(null, null, null)));

		assertThat(stream.read("worker-1", 10, null)).hasSize(1);
		assertThat(stream.read("worker-2", 10, null)).isEmpty();
	}

	@Test
	@DisplayName("처리 대기 메시지의 작업 ID를 읽어도 넘겨받지 않으므로 전달 횟수가 늘지 않는다")
	void findJobIdsReadsWithoutClaiming() {
		String first = stream.publish(new ChatJobMessage(11L, new ChatRequestContext(null, null, null)));
		String second = stream.publish(new ChatJobMessage(12L, new ChatRequestContext(null, null, null)));
		stream.read("worker-1", 10, null);

		var jobIds = stream.findJobIds(List.of(first, second, "0-1"));

		assertThat(jobIds).containsExactly(Map.entry(first, 11L), Map.entry(second, 12L));
		assertThat(stream.findStale(java.time.Duration.ZERO, 10))
				.allSatisfy(pending -> assertThat(pending.deliveryCount()).isEqualTo(1));
	}

	@Test
	@DisplayName("형식이 맞지 않는 메시지는 ACK하고 건너뛴다")
	void malformedMessageIsAckedAndSkipped() {
		redis.opsForStream().add(ChatJobStream.STREAM_KEY, Map.of("unexpected", "value"));

		assertThat(stream.read("worker-1", 10, null)).isEmpty();
		assertThat(pendingCount()).isZero();
	}

	@Test
	@DisplayName("같은 작업의 메시지가 여러 번 와도 QUEUED일 때 한 실행만 선점한다")
	void claimSucceedsOnlyOnceWhileQueued() {
		ChatJob job = queueJob("질문");
		LocalDateTime first = now();

		assertThat(queue.claim(job.getId(), first)).contains(first);
		assertThat(queue.claim(job.getId(), now())).as("이미 RUNNING").isEmpty();
		assertThat(jobs.findById(job.getId())).get().satisfies(found -> {
			assertThat(found.getStatus()).isEqualTo(ChatJobStatus.RUNNING);
			assertThat(found.getStartedAt()).isEqualTo(first);
		});
	}

	@Test
	@DisplayName("여러 Worker가 동시에 같은 작업을 선점해도 하나만 성공한다")
	void concurrentClaimsSucceedOnce() throws Exception {
		ChatJob job = queueJob("질문");
		int workers = 8;
		var barrier = new CyclicBarrier(workers);
		List<Future<Optional<LocalDateTime>>> futures = new ArrayList<>();
		try (var executor = Executors.newFixedThreadPool(workers)) {
			for (int index = 0; index < workers; index++) {
				Callable<Optional<LocalDateTime>> claim = () -> {
					barrier.await(5, TimeUnit.SECONDS);
					return queue.claim(job.getId(), now());
				};
				futures.add(executor.submit(claim));
			}
			int claimed = 0;
			for (Future<Optional<LocalDateTime>> future : futures) {
				if (future.get(10, TimeUnit.SECONDS).isPresent()) {
					claimed++;
				}
			}
			assertThat(claimed).isEqualTo(1);
		}
	}

	@Test
	@DisplayName("돌려놓기는 선점한 실행만 할 수 있고, 돌려놓은 작업은 다시 선점할 수 있다")
	void onlyTheClaimingRunCanRelease() {
		ChatJob job = queueJob("질문");
		LocalDateTime startedAt = now();
		queue.claim(job.getId(), startedAt);

		assertThat(queue.release(job.getId(), startedAt.minusSeconds(1))).as("다른 실행").isZero();
		assertThat(queue.release(job.getId(), startedAt)).isEqualTo(1);

		assertThat(jdbc.queryForMap("select execution_status, started_at from chat_jobs where job_id = ?", job.getId()))
				.containsEntry("execution_status", "QUEUED").containsEntry("started_at", null);
		assertThat(queue.claim(job.getId(), now())).isPresent();
	}

	@Test
	@DisplayName("대기 한도를 넘긴 대기 작업만 찾는다")
	void findsOnlyQueuedJobsPastMaxWait() {
		ChatJob old = queueJob("오래된 질문");
		ChatJob recent = queueJob("방금 질문");
		ChatJob running = queueJob("실행 중 질문");
		queue.claim(running.getId(), now());
		jdbc.update("update chat_jobs set created_at = created_at - interval '1 hour' where job_id in (?, ?)",
				old.getId(), running.getId());

		assertThat(queue.findQueuedOlderThan(Duration.ofMinutes(10), 100)).containsExactly(old.getId());
		assertThat(recent.getId()).isNotNull();
	}

	@Test
	@DisplayName("발행하면 발행 표시가 만료 시간과 함께 남고, 표시가 없는 작업만 골라낸다")
	void publishLeavesMarker() {
		stream.publish(new ChatJobMessage(7L, new ChatRequestContext(null, null, null)));

		assertThat(stream.findUnpublished(List.of(7L, 8L))).containsExactly(8L);
		assertThat(redis.getExpire("chat:jobs:published:7")).isPositive();
		assertThat(stream.findUnpublished(List.of())).isEmpty();
	}

	@Test
	@DisplayName("다시 발행할 차례는 한 번만 잡히고, 표시를 지우면 다시 잡을 수 있다")
	void recoveryPublishIsAtomicAndDeduplicated() {
		assertThat(stream.publishIfAbsent(message(9L))).isTrue();
		assertThat(stream.publishIfAbsent(message(9L))).as("다른 Worker").isFalse();
		assertThat(stream.read("worker-1", 10, null)).hasSize(1);

		stream.clearPublished(9L);

		assertThat(stream.publishIfAbsent(message(9L))).isTrue();
	}

	@Test
	@DisplayName("발행 표시가 있어도 표시가 가리키는 메시지가 스트림에 없으면 발행되지 않은 것으로 보고 다시 발행한다")
	void markPointingToLostMessageIsTreatedAsUnpublished() {
		stream.publish(message(21L));
		redis.delete(ChatJobStream.STREAM_KEY);
		stream.publish(message(22L));
		redis.opsForValue().set("chat:jobs:published:23", "reserved");

		assertThat(stream.findUnpublished(List.of(21L, 22L, 23L)))
				.as("21번 메시지는 스트림과 함께 사라짐, 23번은 이전 버전의 예약 표시")
				.containsExactly(21L, 23L);
		assertThat(stream.publishIfAbsent(message(21L))).isTrue();
		assertThat(stream.publishIfAbsent(message(22L))).as("메시지가 남아 있음").isFalse();
		assertThat(stream.findUnpublished(List.of(21L, 22L))).isEmpty();
	}

	@Test
	@DisplayName("결과는 만료 시간과 함께 Redis에 남는다")
	void resultIsStoredWithTtl() {
		var result = new ChatJobResult(5L, ChatStoreDto.of(true, null));

		results.save(99L, result, Duration.ofMinutes(1));

		assertThat(results.find(99L)).contains(result);
		assertThat(redis.getExpire("chat:jobs:result:99")).isPositive();
		assertThat(results.find(100L)).isEmpty();
	}

	@Test
	@DisplayName("오래 ACK되지 않은 메시지만 전달 횟수와 함께 찾는다")
	void findsOnlyStaleMessagesWithDeliveryCount() {
		stream.publish(message(1L));
		stream.publish(message(2L));
		String stale = stream.read("dead-worker", 10, null).getFirst().messageId();
		leaveIdle(stale, "dead-worker", Duration.ofHours(1), 2);

		assertThat(stream.findStale(Duration.ofMinutes(5), 10))
				.as("방금 받은 2번은 빠짐")
				.containsExactly(new PendingChatJob(stale, "dead-worker", 2));
	}

	@Test
	@DisplayName("방치된 메시지는 여러 Worker가 넘겨받으려 해도 한 곳만 받고, 넘겨받을 때마다 전달 횟수가 는다")
	void staleMessageIsClaimedByOneConsumer() {
		stream.publish(message(1L));
		String messageId = stream.read("dead-worker", 1, null).getFirst().messageId();
		leaveIdle(messageId, "dead-worker", Duration.ofHours(1), 1);

		assertThat(stream.claim("worker-1", Duration.ofMinutes(5), List.of(messageId)))
				.singleElement().satisfies(received -> assertThat(received.message().jobId()).isEqualTo(1L));
		assertThat(stream.claim("worker-2", Duration.ofMinutes(5), List.of(messageId)))
				.as("방금 넘겨받아 더 이상 방치된 메시지가 아님").isEmpty();
		var pending = redis.opsForStream().pending(ChatJobStream.STREAM_KEY, ChatJobStream.GROUP, Range.unbounded(), 10);
		assertThat(pending).singleElement().satisfies(found -> {
			assertThat(found.getConsumerName()).isEqualTo("worker-1");
			assertThat(found.getTotalDeliveryCount()).isEqualTo(2);
		});
	}

	@Test
	@DisplayName("스트림 정리는 ACK된 앞부분만 지우고, 처리 대기와 아직 전달하지 않은 메시지는 남긴다")
	void trimKeepsPendingAndUndeliveredMessages() {
		stream.publish(message(1L));
		assertThat(stream.trimAcknowledged()).as("아직 전달한 메시지가 없음").isZero();
		stream.publish(message(2L));
		stream.publish(message(3L));
		List<ReceivedChatJob> read = stream.read("worker-1", 2, null);
		stream.ack(read.getFirst().messageId());

		assertThat(stream.trimAcknowledged()).isEqualTo(1);
		assertThat(streamJobIds()).as("처리 대기인 2번, 전달 전인 3번").containsExactly(2L, 3L);

		stream.ack(read.get(1).messageId());
		stream.trimAcknowledged();

		assertThat(streamJobIds()).as("마지막으로 전달한 메시지와 그 뒤는 남김").containsExactly(2L, 3L);
		assertThat(stream.read("worker-1", 10, null)).as("남은 메시지는 그대로 전달됨")
				.singleElement().satisfies(received -> assertThat(received.message().jobId()).isEqualTo(3L));
	}

	@Test
	@DisplayName("Redis 데이터가 비워져 그룹이 없으면 넘겨받을 메시지도, 지울 메시지도 없다")
	void missingGroupHasNothingToReclaimOrTrim() {
		redis.delete(ChatJobStream.STREAM_KEY);

		assertThat(stream.findStale(Duration.ofMinutes(5), 10)).isEmpty();
		assertThat(stream.trimAcknowledged()).isZero();
	}

	@Test
	@DisplayName("넘겨받기는 기준 시각 전에 시작한 실행 중 작업만 되고, 이전 실행은 더 이상 작업을 가지지 않는다")
	void takeOverOnlyStuckRun() {
		ChatJob job = queueJob("질문");
		assertThat(queue.takeOver(job.getId(), now(), now())).as("대기 중인 작업").isEmpty();
		LocalDateTime stuckAt = now().minusHours(1);
		queue.claim(job.getId(), stuckAt);
		LocalDateTime takenAt = now();

		assertThat(queue.takeOver(job.getId(), takenAt, stuckAt)).as("기준 시각 전에 시작하지 않음").isEmpty();
		assertThat(queue.takeOver(job.getId(), takenAt, takenAt.minusMinutes(2))).contains(takenAt);
		assertThat(queue.takeOver(job.getId(), now(), now().minusMinutes(2))).as("방금 넘겨받은 실행").isEmpty();

		assertThat(jobs.findById(job.getId())).get().satisfies(found -> {
			assertThat(found.isRunningSince(takenAt)).isTrue();
			assertThat(found.isRunningSince(stuckAt)).as("이전 실행은 결과를 기록할 수 없음").isFalse();
		});
		assertThat(queue.release(job.getId(), stuckAt)).as("이전 실행은 돌려놓을 수도 없음").isZero();
	}

	@Test
	@DisplayName("기준 시각 전에 시작해 아직 실행 중인 작업만 찾는다")
	void findsOnlyRunningJobsStartedBefore() {
		ChatJob stuck = queueJob("멈춘 질문");
		ChatJob recent = queueJob("방금 시작한 질문");
		queueJob("대기 중인 질문");
		queue.claim(stuck.getId(), now().minusHours(1));
		queue.claim(recent.getId(), now());

		assertThat(queue.findRunningStartedBefore(now().minusMinutes(10), 100)).containsExactly(stuck.getId());
	}

	@Test
	@DisplayName("작업은 지워진 시도와 함께 지워진다")
	void jobIsDeletedWithItsAttempt() {
		queueJob("질문");

		attempts.deleteAllInBatch();

		assertThat(jobs.count()).isZero();
	}

	private static ChatJobMessage message(long jobId) {

		return new ChatJobMessage(jobId, new ChatRequestContext(null, null, null));
	}

	@Test
	void expiredQueuedJobCannotBeClaimedBeforeCleanupRuns() {
		ChatJob job = queueJob("만료된 질문");
		jdbc.update("update chat_jobs set created_at = CURRENT_TIMESTAMP - interval '11 minutes' where job_id = ?", job.getId());
		assertThat(queue.claim(job.getId(), now())).isEmpty();
		assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(ChatJobStatus.QUEUED);
	}

	@Test
	void heartbeatProtectsLiveJobAndOldOwnerCannotRenewIt() {
		ChatJob job = queueJob("느린 질문");
		LocalDateTime old = now().minusMinutes(20);
		queue.claim(job.getId(), old);
		LocalDateTime current = now();
		assertThat(queue.heartbeat(job.getId(), old, current)).isEqualTo(1);
		assertThat(queue.takeOver(job.getId(), current, current.minusMinutes(5))).isEmpty();
		assertThat(queue.findRunningStartedBefore(current.minusMinutes(10), 100)).isEmpty();
		jdbc.update("update chat_jobs set heartbeat_at = ? where job_id = ?", old, job.getId());
		assertThat(queue.takeOver(job.getId(), current, current.minusMinutes(5))).contains(current);
		assertThat(queue.heartbeat(job.getId(), old, now())).isZero();
	}

	@Test
	void legacyReservationAndLostStreamDoNotPreventRecoveryPublish() {
		redis.opsForValue().set("chat:jobs:published:9", "reserved", Duration.ofMinutes(11));
		assertThat(stream.findUnpublished(List.of(9L))).containsExactly(9L);
		assertThat(stream.publishIfAbsent(message(9L))).isTrue();
		redis.delete(ChatJobStream.STREAM_KEY);
		assertThat(stream.findUnpublished(List.of(9L))).containsExactly(9L);
		assertThat(stream.publishIfAbsent(message(9L))).isTrue();
		stream.ensureGroup();
		assertThat(stream.read("worker", 10, null)).hasSize(1);
	}

	@Test
	void failedAtomicPublishLeavesNoReservationAndCanBeRetried() {
		redis.delete(ChatJobStream.STREAM_KEY);
		redis.opsForValue().set(ChatJobStream.STREAM_KEY, "wrong-type");
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> stream.publishIfAbsent(message(9L)))
				.isInstanceOf(org.springframework.dao.DataAccessException.class);
		assertThat(redis.opsForValue().get("chat:jobs:published:9")).isNull();
		redis.delete(ChatJobStream.STREAM_KEY);
		assertThat(stream.publishIfAbsent(message(9L))).isTrue();
	}

	@Test
	void concurrentRecoveryPublishesOneMessageWithItsContext() throws Exception {
		var message = new ChatJobMessage(91L, new ChatRequestContext(37.5, 127.0, RegionSido.SEOUL));
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			CyclicBarrier barrier = new CyclicBarrier(8);
			List<Future<Boolean>> futures = new ArrayList<>();
			for (int index = 0; index < 8; index++) futures.add(executor.submit(() -> {
				barrier.await(5, TimeUnit.SECONDS);
				return stream.publishIfAbsent(message);
			}));
			int published = 0;
			for (Future<Boolean> future : futures) if (future.get(5, TimeUnit.SECONDS)) published++;
			assertThat(published).isEqualTo(1);
		}
		assertThat(stream.read("worker", 10, null)).singleElement()
				.satisfies(received -> assertThat(received.message()).isEqualTo(message));
	}

	// consumer가 받아 놓고 idle 동안 ACK 안 한 메시지로 만듦. 전달 횟수는 deliveries
	private void leaveIdle(String messageId, String consumer, Duration idle, long deliveries) {
		redis.opsForStream().claim(ChatJobStream.STREAM_KEY, ChatJobStream.GROUP, consumer,
				XClaimOptions.minIdle(Duration.ZERO).ids(messageId).idle(idle).retryCount(deliveries));
	}

	private List<Long> streamJobIds() {
		return redis.opsForStream().range(ChatJobStream.STREAM_KEY, Range.unbounded()).stream()
				.map(record -> Long.parseLong(String.valueOf(record.getValue().get("jobId"))))
				.toList();
	}

	private long pendingCount() {
		return redis.opsForStream().pending(ChatJobStream.STREAM_KEY, ChatJobStream.GROUP).getTotalPendingMessages();
	}

	private ChatJob queueJob(String question) {
		var attempt = attempts.saveAndFlush(new AnswerAttemptsHistory(null, conversations.createGuestConversation(),
				question, 1, UUID.randomUUID().toString().replace("-", "").repeat(2), LocalDateTime.now(), "", ""));
		return jobs.saveAndFlush(ChatJob.queue(attempt.getId()));
	}

	private static LocalDateTime now() {
		return LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
	}
}
