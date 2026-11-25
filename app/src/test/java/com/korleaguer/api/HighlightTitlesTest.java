package com.korleaguer.api;

import com.korleaguer.api.game.HighlightTitles;

import com.korleaguer.domain.HighlightTitle;
import com.korleaguer.domain.HighlightTitleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 화면용 API의 하이라이트 한국어 제목(HighlightTitles): 저장된 번역만 읽는다(이슈 #237).
 * 번역기를 모르므로 화면 요청으로는 AI를 부르지 않고 번역 표에 쓰지도 않는다. 번역은 수집(ingest.HighlightTitleIngest)이 한다
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class HighlightTitlesTest {

    private static final String SOLO = "Jung Hoo Lee's solo home run (7)";
    private static final String CATCH = "Jung Hoo Lee's insane underhanded catch in right";

    @Autowired
    private HighlightTitleRepository repository;

    private HighlightTitles titles;

    @BeforeEach
    void setUp() {
        titles = new HighlightTitles(repository);
    }

    @Test
    @DisplayName("번역된 제목만 보여 주고 없는 제목은 번역하거나 저장하지 않는다")
    void aStoredTitleIsShownAndAMissingOneIsLeftOutWithoutBeingTranslatedOrSaved() {
        // given
        store(SOLO, "솔로 홈런 (시즌 7호)", "fake/t1");
        long rows = repository.count();

        // when // then
        assertThat(titles.koreanOf(List.of(SOLO, CATCH, SOLO))).isEqualTo(Map.of(SOLO, "솔로 홈런 (시즌 7호)"));
        // 없는 제목은 다시 물어도 그대로 없다(화면 요청은 번역을 시작하지 않는다)
        assertThat(titles.koreanOf(List.of(CATCH))).isEmpty();
        assertThat(repository.count()).isEqualTo(rows);
        assertThat(repository.findByTitleEn(CATCH)).isEmpty();
    }

    @Test
    @DisplayName("옛 규칙으로 번역한 제목도 그대로 보여 준다")
    void anOldEditionIsStillShown() {
        // given
        // 번역 규칙 판이 낡은 번역도 그대로 보여 준다(수집이 다시 옮기면 바뀐다)
        store(SOLO, "옛 번역", "fake/t0");

        // when // then
        assertThat(titles.koreanOf(List.of(SOLO))).isEqualTo(Map.of(SOLO, "옛 번역"));
        assertThat(repository.findByTitleEn(SOLO)).hasValueSatisfying(
                t -> assertThat(t.getPromptVersion()).isEqualTo("fake/t0"));
    }

    @Test
    @DisplayName("비어 있거나 없는 제목은 건너뛴다")
    void blankOrMissingTitlesAreSkipped() {
        // when // then
        assertThat(titles.koreanOf(Arrays.asList(" ", null))).isEmpty();
        assertThat(titles.koreanOf(List.of())).isEmpty();
    }

    private void store(String titleEn, String titleKo, String version) {
        HighlightTitle row = new HighlightTitle(titleEn);
        row.translated(titleKo, version, Instant.parse("2026-11-19T00:00:00Z"));
        repository.save(row);
    }
}
