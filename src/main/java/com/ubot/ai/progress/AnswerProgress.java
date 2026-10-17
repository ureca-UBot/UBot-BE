package com.ubot.ai.progress;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** 동기 답변 생성 파이프라인의 진행 관찰자. 작업 스레드 안에서만 설치하고 반드시 해제합니다. */
public final class AnswerProgress {
    public enum Phase { WAITING, SEARCHING, COLLECTING_CONTEXT, WAITING_FOR_MODEL, GENERATING }

    private static final ThreadLocal<Consumer<Phase>> OBSERVER = new ThreadLocal<>();

    private AnswerProgress() {}

    public static void report(Phase phase) {
        Consumer<Phase> observer = OBSERVER.get();
        if (observer != null) observer.accept(phase);
    }

    public static <T> T observe(Consumer<Phase> observer, Supplier<T> computation) {
        Consumer<Phase> previous = OBSERVER.get();
        OBSERVER.set(observer);
        try {
            return computation.get();
        } finally {
            if (previous == null) OBSERVER.remove();
            else OBSERVER.set(previous);
        }
    }
}
