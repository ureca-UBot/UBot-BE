package com.ubot.chat.job.stream;

import com.ubot.chat.job.dto.ChatJobResult;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

// 결과 캐시(chat:jobs:result:{jobId}). 원본은 chat_jobs.result_payload
@Component
public class ChatJobResultStore {
	private static final String KEY_PREFIX = "chat:jobs:result:";

	private final StringRedisTemplate redis;
	private final ObjectMapper objectMapper;

	public ChatJobResultStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
		this.redis = redis;
		this.objectMapper = objectMapper;
	}

	public void save(long jobId, ChatJobResult result, Duration ttl) {
		redis.opsForValue().set(KEY_PREFIX + jobId, objectMapper.writeValueAsString(result), ttl);
	}

	public Optional<ChatJobResult> find(long jobId) {
		String json = redis.opsForValue().get(KEY_PREFIX + jobId);
		return json == null ? Optional.empty() : Optional.of(objectMapper.readValue(json, ChatJobResult.class));
	}
}
