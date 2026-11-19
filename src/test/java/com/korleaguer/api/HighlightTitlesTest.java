package com.korleaguer.api;

import com.korleaguer.config.DataMode;
import com.korleaguer.domain.HighlightTitle;
import com.korleaguer.domain.HighlightTitleRepository;
import com.korleaguer.news.HighlightTitleTranslator;
import com.korleaguer.news.Summarizer;
import com.korleaguer.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 하이라이트 제목 번역(HighlightTitles): 저장된 것만 바로 주고 뒤에서 옮기기, 한 번만 옮기기, 실패 쉬기. 번역기는 가짜다 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class HighlightTitlesTest {

    private static final String SOLO = "Jung Hoo Lee's solo home run (7)";
    private static final String CATCH = "Jung Hoo Lee's insane underhanded catch in right";

    @Autowired
    private HighlightTitleRepository repository;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-19T00:00:00Z"));
    private final FakeTranslator translator = new FakeTranslator();
    /** 뒤 스레드 일을 부른 자리에서 바로 한다(순서를 시험하려고) */
    private final DirectExecutor executor = new DirectExecutor();
    private HighlightTitles titles;

    @BeforeEach
    void setUp() {
        titles = new HighlightTitles(repository, translator, DataMode.MLB, clock, executor);
    }

    @Test
    void aMissingTitleIsNullNowTranslatedInTheBackgroundAndUsedFromTheNextCall() {
        assertThat(titles.koreanOf(List.of(SOLO), "이정후", "Jung Hoo Lee")).isEmpty();
        assertThat(translator.asked).containsExactly(SOLO);
        // 번역기는 영상이 뜨는 창의 선수를 안다(그 선수 이름을 빼고 옮기게)
        assertThat(translator.players).containsExactly("이정후/Jung Hoo Lee");

        assertThat(titles.koreanOf(List.of(SOLO), "이정후", "Jung Hoo Lee")).isEqualTo(Map.of(SOLO, "솔로 홈런 (시즌 7호)"));
        // 같은 제목은 다시 옮기지 않는다
        assertThat(translator.asked).containsExactly(SOLO);
        assertThat(repository.findByTitleEn(SOLO)).hasValueSatisfying(
                t -> assertThat(t.getPromptVersion()).isEqualTo("fake/t1"));
    }

    @Test
    void theSameTitleInOneBatchIsTranslatedOnce() {
        titles.koreanOf(List.of(SOLO, SOLO, CATCH), "이정후", "Jung Hoo Lee");
        assertThat(translator.asked).containsExactly(SOLO, CATCH);
    }

    @Test
    void titlesAlreadyInKoreanOrBlankAreNeverSent() {
        assertThat(titles.koreanOf(java.util.Arrays.asList("솔로 홈런", " ", null), "이정후", "Jung Hoo Lee")).isEmpty();
        assertThat(translator.asked).isEmpty();
    }

    @Test
    void anOldEditionIsShownAndTranslatedAgain() {
        HighlightTitle old = new HighlightTitle(SOLO);
        old.translated("옛 번역", "fake/t0", clock.instant());
        repository.save(old);

        assertThat(titles.koreanOf(List.of(SOLO), "이정후", "Jung Hoo Lee")).isEqualTo(Map.of(SOLO, "옛 번역"));
        assertThat(translator.asked).containsExactly(SOLO);
        assertThat(titles.koreanOf(List.of(SOLO), "이정후", "Jung Hoo Lee")).isEqualTo(Map.of(SOLO, "솔로 홈런 (시즌 7호)"));
    }

    @Test
    void anUnusableAnswerIsNotAskedAgainUntilTheRetryTime() {
        translator.answer = Optional.empty();
        titles.koreanOf(List.of(SOLO), "이정후", "Jung Hoo Lee");
        titles.koreanOf(List.of(SOLO), "이정후", "Jung Hoo Lee");
        assertThat(translator.asked).containsExactly(SOLO);

        clock.advance(HighlightTitles.RETRY_AFTER.plusSeconds(1));
        translator.answer = Optional.of("솔로 홈런 (시즌 7호)");
        titles.koreanOf(List.of(SOLO), "이정후", "Jung Hoo Lee");
        assertThat(translator.asked).containsExactly(SOLO, SOLO);
        assertThat(titles.koreanOf(List.of(SOLO), "이정후", "Jung Hoo Lee")).containsKey(SOLO);
    }

    @Test
    void whenTheServiceFailsEveryTitlePausesThenTriesAgain() {
        translator.fail = true;
        titles.koreanOf(List.of(SOLO), "이정후", "Jung Hoo Lee");
        titles.koreanOf(List.of(CATCH), "이정후", "Jung Hoo Lee");
        assertThat(translator.asked).containsExactly(SOLO);

        clock.advance(HighlightTitles.PAUSE.plusSeconds(1));
        translator.fail = false;
        titles.koreanOf(List.of(CATCH), "이정후", "Jung Hoo Lee");
        assertThat(translator.asked).containsExactly(SOLO, CATCH);
    }

    @Test
    void withTheTranslatorOffNothingIsAskedButStoredTitlesStillShow() {
        HighlightTitle stored = new HighlightTitle(SOLO);
        stored.translated("솔로 홈런 (시즌 7호)", "other/t1", clock.instant());
        repository.save(stored);
        HighlightTitles off = new HighlightTitles(repository, HighlightTitleTranslator.OFF, DataMode.MLB, clock, executor);

        assertThat(off.koreanOf(List.of(SOLO, CATCH), "이정후", "Jung Hoo Lee")).isEqualTo(Map.of(SOLO, "솔로 홈런 (시즌 7호)"));
        assertThat(translator.asked).isEmpty();
    }

    @Test
    void sampleModeNeverCallsTheTranslator() {
        new HighlightTitles(repository, translator, DataMode.SAMPLE, clock, executor).koreanOf(List.of(SOLO), "이정후", "Jung Hoo Lee");
        assertThat(translator.asked).isEmpty();
    }

    static class FakeTranslator implements HighlightTitleTranslator {
        final List<String> asked = new ArrayList<>();
        final List<String> players = new ArrayList<>();
        Optional<String> answer = Optional.of("솔로 홈런 (시즌 7호)");
        boolean fail;

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public Optional<String> translate(String titleEn, String playerKo, String playerEn) {
            asked.add(titleEn);
            players.add(playerKo + "/" + playerEn);
            if (fail) {
                throw new Summarizer.Failure("down", null);
            }
            return answer;
        }

        @Override
        public String version() {
            return "fake/t1";
        }
    }

    static class DirectExecutor extends AbstractExecutorService {
        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }
}
