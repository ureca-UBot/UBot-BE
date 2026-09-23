package com.ubot.chat.mvp.service;

import com.ubot.chat.mvp.config.ChatMvpSettings;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/** Private bounded workers; does not replace the shared Spring MVC or @Async executor. */
@Component
public class ChatJobRunner implements DisposableBean {
    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor timers;
    private final Semaphore admission;

    public ChatJobRunner(ChatMvpSettings settings) {
        workers = new ThreadPoolExecutor(settings.workers(), settings.workers(), 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(settings.queueCapacity()),
                Thread.ofPlatform().daemon(true).name("chat-mvp-worker-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        timers = new ScheduledThreadPoolExecutor(2,
                Thread.ofPlatform().daemon(true).name("chat-mvp-timer-", 0).factory());
        timers.setRemoveOnCancelPolicy(true);
        admission = new Semaphore(settings.workers() + settings.queueCapacity());
    }

    public boolean reserve() { return admission.tryAcquire(); }
    public void release() { admission.release(); }

    /** Caller owns the permit until execute accepts; thereafter the wrapper always releases it. */
    public void execute(FutureTask<Void> task) {
        workers.execute(() -> {
            try { task.run(); }
            finally { release(); }
        });
    }

    public ScheduledFuture<?> after(long millis, Runnable task) {
        return timers.schedule(task, Math.max(1, millis), TimeUnit.MILLISECONDS);
    }

    public ScheduledFuture<?> heartbeat(Runnable task) {
        return timers.scheduleAtFixedRate(task, 15, 15, TimeUnit.SECONDS);
    }

    @Override
    public void destroy() {
        workers.shutdownNow();
        timers.shutdownNow();
    }
}
