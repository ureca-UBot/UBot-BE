package com.ubot.chat.job.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ubot.chat.exception.ChatException;
import com.ubot.common.GlobalExceptionHandler;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;

class ChatJobReadExecutorLoggingTest {

	private final ChatJobReadExecutor reads = new ChatJobReadExecutor(Duration.ofMillis(1500), 8);
	private final Logger readLogger = (Logger) LoggerFactory.getLogger(ChatJobReadExecutor.class);
	private final Logger handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
	private final ListAppender<ILoggingEvent> events = new ListAppender<>();
	private Level previousLevel;

	@BeforeEach
	void capture() {
		previousLevel = readLogger.getLevel();
		readLogger.setLevel(Level.DEBUG);
		events.start();
		readLogger.addAppender(events);
		handlerLogger.addAppender(events);
	}

	@AfterEach
	void close() {
		readLogger.detachAppender(events);
		handlerLogger.detachAppender(events);
		readLogger.setLevel(previousLevel);
		events.stop();
		reads.close();
	}

	@Test
	void onlyHandlerWarnsWhileReadFailureDetailsStayAtDebug() {
		var failure = assertThrows(ChatException.class, () -> reads.execute(
				new MockHttpServletRequest("GET", "/chat/jobs/123"), () -> {
					throw new DataAccessResourceFailureException("INSERT INTO answers 비공개 질문 secret-token");
				}));

		var response = new GlobalExceptionHandler().handleChatException(failure);

		assertThat(response.getStatusCode().value()).isEqualTo(503);
		assertThat(events.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.DEBUG, Level.WARN);
		assertThat(events.list).extracting(ILoggingEvent::getFormattedMessage)
				.anySatisfy(message -> assertThat(message).contains("원인=DataAccessResourceFailureException", "실행중조회수="))
				.allSatisfy(message -> assertThat(message).contains("CHAT-025")
						.doesNotContain("INSERT INTO answers", "비공개 질문", "secret-token"));
		assertThat(events.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
	}
}
