package com.ubot.chat.job.support;

import com.ubot.chat.job.stream.ChatJobStream;
import com.ubot.chat.job.stream.ReceivedChatJob;
import com.ubot.chat.job.worker.ChatJobRunner;
import com.ubot.chat.job.worker.ChatJobRecoveryService;
import com.ubot.chat.job.worker.ChatWorkerProperties;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** 폴러를 시작하지 않고 메시지를 처리하는 테스트 도구. 실행은 실제 Runner에 맡깁니다. */
public record ChatJobTestDriver(ChatJobStream stream, ChatJobRunner runner,
        ChatJobRecoveryService recovery, ChatWorkerProperties properties) {
    public int processAvailableJobs() {
        stream.ensureGroup();
        int processed = 0;
        List<ReceivedChatJob> received;
        while (!(received = stream.read(properties.consumerName(), 1, null)).isEmpty()) {
            try {
                runner.submit(received.getFirst()).get(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("답변 실행 대기가 중단되었습니다.", interrupted);
            } catch (Exception failure) {
                throw new AssertionError("답변 실행이 완료되지 않았습니다.", failure);
            }
            processed++;
        }
        return processed;
    }

    public void recover() {
        try {
            recovery.recover().get(30, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("복구 대기가 중단되었습니다.", interrupted);
        } catch (Exception failure) {
            throw new AssertionError("복구 작업이 완료되지 않았습니다.", failure);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {
        @Bean
        ChatJobTestDriver chatJobTestDriver(ChatJobStream stream, ChatJobRunner runner,
                ChatJobRecoveryService recovery, ChatWorkerProperties properties) {
            return new ChatJobTestDriver(stream, runner, recovery, properties);
        }
    }
}
