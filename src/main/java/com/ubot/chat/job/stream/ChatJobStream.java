package com.ubot.chat.job.stream;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands.TrimOptions;
import org.springframework.data.redis.connection.RedisStreamCommands.XTrimOptions;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamInfo.XInfoGroup;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

// Worker에 작업을 전달하는 Redis Streams(chat:jobs, 그룹 chat-workers). 상태·결과 원본은 DB이고 스트림은 전달만 함
// 중복 전달 가능하므로 Worker가 DB 상태로 중복 실행을 막음. ACK는 DB 커밋 뒤에만 보냄
// 발행 표시(chat:jobs:published:{jobId}) 값은 메시지 ID. 표시가 없거나 그 ID에 같은 작업의 메시지가 없으면 미발행으로 봄
@Slf4j
@Component
public class ChatJobStream {
	public static final String STREAM_KEY = "chat:jobs";
	public static final String GROUP = "chat-workers";
	private static final String PUBLISHED_KEY_PREFIX = "chat:jobs:published:";
	// 스트림을 지운 뒤 같은 ms에 XADD하면 메시지 ID가 다시 쓰이므로 ID와 jobId가 모두 맞아야 같은 메시지로 봄
	// 메시지 ID가 아닌 값(이전 버전 reserved 등)은 없는 것으로 봄
	private static final String HOLDS_JOB = """
			local function holds_job(stream, id, job_id)
			    local found = redis.pcall('XRANGE', stream, id, id)
			    if type(found) ~= 'table' or #found == 0 then
			        return false
			    end
			    local fields = found[1][2]
			    for i = 1, #fields, 2 do
			        if fields[i] == 'jobId' then
			            return fields[i + 1] == job_id
			        end
			    end
			    return false
			end
			""";
	// 메시지와 발행 표시를 함께 저장함. 표시가 가리키는 메시지가 남아 있을 때만 발행된 것으로 봄
	// ARGV: TTL(ms), jobId, 메시지 필드…
	private static final RedisScript<Long> PUBLISH_IF_ABSENT = RedisScript.of(HOLDS_JOB + """
			local mark = redis.call('GET', KEYS[2])
			if mark and holds_job(KEYS[1], mark, ARGV[2]) then
			    return 0
			end
			local id = redis.call('XADD', KEYS[1], '*', unpack(ARGV, 3))
			redis.call('SET', KEYS[2], id, 'PX', ARGV[1])
			return 1
			""", Long.class);
	// ARGV: (메시지 ID, jobId) 쌍. 그 작업의 메시지가 스트림에 없는 쌍의 순번(1부터)
	@SuppressWarnings("rawtypes")
	private static final RedisScript<List> MISSING_MESSAGES = RedisScript.of(HOLDS_JOB + """
			local missing = {}
			for index = 1, #ARGV / 2 do
			    if not holds_job(KEYS[1], ARGV[index * 2 - 1], ARGV[index * 2]) then
			        missing[#missing + 1] = index
			    end
			end
			return missing
			""", List.class);

	private final StringRedisTemplate redis;
	private final Duration publishedTtl;

	// 대기 한도가 지나면 작업이 실패로 끝나므로 표시는 그보다 조금만 길게 둠
	public ChatJobStream(StringRedisTemplate redis, @Value("${app.chat.worker.max-queue-wait}") Duration maxQueueWait) {
		this.redis = redis;
		this.publishedTtl = maxQueueWait.plusMinutes(1);
	}

	public void ensureGroup() {
		try {
			redis.execute((RedisCallback<String>) connection -> connection.streamCommands().xGroupCreate(
					STREAM_KEY.getBytes(StandardCharsets.UTF_8), GROUP, ReadOffset.from("0"), true));
			log.info("답변 작업 스트림 그룹을 만들었습니다: 스트림={}, 그룹={}", STREAM_KEY, GROUP);
		} catch (DataAccessException exception) {
			if (!hasErrorCode(exception, "BUSYGROUP")) {
				throw exception;
			}
		}
	}

	// 표시를 못 남기면 정리 작업이 한 번 더 발행할 수 있음. Worker가 중복을 걸러 내므로 성공으로 봄
	public String publish(ChatJobMessage message) {
		String messageId = redis.opsForStream()
				.add(StreamRecords.newRecord().in(STREAM_KEY).ofMap(message.toFields()))
				.getValue();
		try {
			redis.opsForValue().set(publishedKey(message.jobId()), messageId, publishedTtl);
		} catch (RuntimeException exception) {
			log.warn("답변 작업의 발행 표시를 남기지 못했습니다. 정리 작업이 한 번 더 발행할 수 있습니다: 작업ID={}",
					message.jobId(), exception);
		}
		return messageId;
	}

	public List<Long> findUnpublished(List<Long> jobIds) {
		if (jobIds.isEmpty()) {
			return List.of();
		}
		if (!Boolean.TRUE.equals(redis.hasKey(STREAM_KEY))) {
			return jobIds;
		}
		List<String> marks = redis.opsForValue().multiGet(jobIds.stream().map(ChatJobStream::publishedKey).toList());
		boolean[] unpublished = new boolean[jobIds.size()];
		List<Integer> markedIndexes = new ArrayList<>();
		List<String> markedPairs = new ArrayList<>();
		for (int index = 0; index < jobIds.size(); index++) {
			String mark = marks == null ? null : marks.get(index);
			if (mark == null) {
				unpublished[index] = true;
			} else {
				markedIndexes.add(index);
				markedPairs.add(mark);
				markedPairs.add(String.valueOf(jobIds.get(index)));
			}
		}
		if (!markedPairs.isEmpty()) {
			List<?> missing = redis.execute(MISSING_MESSAGES, List.of(STREAM_KEY), markedPairs.toArray());
			for (Object position : missing == null ? List.of() : missing) {
				unpublished[markedIndexes.get(((Number) position).intValue() - 1)] = true;
			}
		}
		List<Long> result = new ArrayList<>();
		for (int index = 0; index < jobIds.size(); index++) {
			if (unpublished[index]) {
				result.add(jobIds.get(index));
			}
		}
		return result;
	}

	// 중복 재발행을 막음
	public boolean publishIfAbsent(ChatJobMessage message) {
		List<String> args = new ArrayList<>();
		args.add(String.valueOf(publishedTtl.toMillis()));
		args.add(String.valueOf(message.jobId()));
		message.toFields().forEach((key, value) -> { args.add(key); args.add(value); });
		return Long.valueOf(1).equals(redis.execute(PUBLISH_IF_ABSENT,
				List.of(STREAM_KEY, publishedKey(message.jobId())), args.toArray()));
	}

	// 재발행 실패 시 다음 정리에서 다시 시도하게 함
	public void clearPublished(long jobId) {
		redis.delete(publishedKey(jobId));
	}

	// block이 null이면 기다리지 않음
	public List<ReceivedChatJob> read(String consumer, int count, Duration block) {
		StreamReadOptions options = StreamReadOptions.empty().count(count);
		if (block != null) {
			options = options.block(block);
		}
		return toReceived(redis.opsForStream().read(
				Consumer.from(GROUP, consumer), options, StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())));
	}

	// 오래된 순, 전달 횟수 포함. 그룹이 없으면(Redis 초기화) 빈 목록
	public List<PendingChatJob> findStale(Duration minIdle, int count) {
		try {
			return redis.opsForStream().pending(STREAM_KEY, GROUP, Range.unbounded(), count, minIdle).stream()
					.map(pending -> new PendingChatJob(
							pending.getIdAsString(), pending.getConsumerName(), pending.getTotalDeliveryCount()))
					.toList();
		} catch (DataAccessException exception) {
			if (isMissingGroup(exception)) {
				return List.of();
			}
			throw exception;
		}
	}

	// 내용만 읽으므로 전달 횟수는 늘지 않음. 지워졌거나 형식이 틀린 메시지는 빠짐
	public Map<String, Long> findJobIds(List<String> messageIds) {
		Map<String, Long> jobIds = new LinkedHashMap<>();
		for (String messageId : messageIds) {
			List<MapRecord<String, Object, Object>> records = redis.opsForStream()
					.range(STREAM_KEY, Range.closed(messageId, messageId));
			if (records == null || records.isEmpty()) {
				continue;
			}
			try {
				jobIds.put(messageId, ChatJobMessage.fromFields(records.getFirst().getValue()).jobId());
			} catch (IllegalArgumentException exception) {
				log.warn("형식이 맞지 않는 답변 작업 메시지라 작업 ID를 읽지 못했습니다: 메시지ID={}", messageId);
			}
		}
		return jobIds;
	}

	// XCLAIM이 minIdle을 다시 확인해 한 Worker만 받음. 넘길 때마다 전달 횟수가 늘어남
	public List<ReceivedChatJob> claim(String consumer, Duration minIdle, List<String> messageIds) {
		if (messageIds.isEmpty()) {
			return List.of();
		}
		return toReceived(redis.opsForStream().claim(STREAM_KEY, GROUP, consumer, minIdle,
				messageIds.stream().map(RecordId::of).toArray(RecordId[]::new)));
	}

	// ACK 안 된 가장 오래된 메시지(없으면 마지막 전달 메시지)보다 앞만 지움
	public long trimAcknowledged() {
		if (!Boolean.TRUE.equals(redis.hasKey(STREAM_KEY))) {
			return 0;
		}
		XInfoGroup group = redis.opsForStream().groups(STREAM_KEY).stream()
				.filter(found -> GROUP.equals(found.groupName()))
				.findFirst()
				.orElse(null);
		if (group == null || group.lastDeliveredId() == null || "0-0".equals(group.lastDeliveredId())) {
			return 0;
		}
		PendingMessagesSummary pending = redis.opsForStream().pending(STREAM_KEY, GROUP);
		String keepFrom = pending.getTotalPendingMessages() > 0 ? pending.minMessageId() : group.lastDeliveredId();
		Long trimmed = redis.opsForStream().trim(STREAM_KEY, XTrimOptions.trim(TrimOptions.minId(RecordId.of(keepFrom))));
		return trimmed == null ? 0 : trimmed;
	}

	public void ack(String messageId) {
		redis.opsForStream().acknowledge(STREAM_KEY, GROUP, messageId);
	}

	// 형식이 틀린 메시지는 ACK하고 건너뜀
	private List<ReceivedChatJob> toReceived(List<MapRecord<String, Object, Object>> records) {
		List<ReceivedChatJob> received = new ArrayList<>();
		for (MapRecord<String, Object, Object> record : records == null ? List.<MapRecord<String, Object, Object>>of() : records) {
			String messageId = record.getId().getValue();
			try {
				received.add(new ReceivedChatJob(messageId, ChatJobMessage.fromFields(record.getValue())));
			} catch (IllegalArgumentException exception) {
				log.error("형식이 맞지 않는 답변 작업 메시지를 건너뜁니다: 메시지ID={}, 필드={}", messageId, record.getValue().keySet());
				ack(messageId);
			}
		}
		return received;
	}

	// 그룹이나 스트림이 없는 경우(Redis 초기화 등). 그룹을 다시 만들면 됨
	public static boolean isMissingGroup(Exception exception) {
		return hasErrorCode(exception, "NOGROUP");
	}

	private static String publishedKey(long jobId) {
		return PUBLISHED_KEY_PREFIX + jobId;
	}

	private static boolean hasErrorCode(Exception exception, String code) {
		Throwable cause = NestedExceptionUtils.getMostSpecificCause(exception);
		return cause.getMessage() != null && cause.getMessage().contains(code);
	}
}
