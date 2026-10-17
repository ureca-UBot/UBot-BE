package com.ubot.ai.progress;

import static org.assertj.core.api.Assertions.*;
import com.ubot.ai.progress.AnswerProgress.Phase;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnswerProgressTest {
    @Test
    void observerIsRemovedEvenAfterFailure() {
        List<Phase> phases = new ArrayList<>();
        assertThatThrownBy(() -> AnswerProgress.observe(phases::add, () -> {
            AnswerProgress.report(Phase.SEARCHING);
            throw new IllegalStateException("검색 실패");
        })).isInstanceOf(IllegalStateException.class);
        AnswerProgress.report(Phase.GENERATING);
        assertThat(phases).containsExactly(Phase.SEARCHING);
    }

    @Test
    void concurrentJobsDoNotShareObservers() throws Exception {
        List<Phase> phases = new ArrayList<>();
        AnswerProgress.observe(phases::add, () -> {
            Thread other = Thread.ofVirtual().start(() -> AnswerProgress.report(Phase.GENERATING));
            try { other.join(); } catch (InterruptedException e) { throw new AssertionError(e); }
            AnswerProgress.report(Phase.SEARCHING);
            return null;
        });
        assertThat(phases).containsExactly(Phase.SEARCHING);
    }
}
