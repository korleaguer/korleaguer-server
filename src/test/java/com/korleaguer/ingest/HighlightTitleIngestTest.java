package com.korleaguer.ingest;

import com.korleaguer.config.DataMode;
import com.korleaguer.domain.GameDetail;
import com.korleaguer.domain.HighlightTitle;
import com.korleaguer.domain.HighlightTitleRepository;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerGameDetail;
import com.korleaguer.domain.PlayerGameDetailRepository;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.news.HighlightTitleTranslator;
import com.korleaguer.news.Summarizer;
import com.korleaguer.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Limit;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 하이라이트 제목 번역 수집(HighlightTitleIngest, 이슈 #237): 번역 대상 고르기, 실행당 상한, 한 번만 옮기기, 규칙 판이 바뀌면 다시 옮기기,
 * 못 옮긴 제목·AI 장애 때 쉬기, 번역기 꺼짐·샘플 모드, 서버를 다시 띄운 뒤 이어 가기, 밀린 것이 없으면 저장된 상세를 다시 읽지 않기.
 * 번역기는 가짜다. 저장된 상세(player_game_detail)는 테스트마다 직접 넣는다(샘플 데이터의 상세에는 하이라이트가 없다).
 * 리포지토리는 부른 것을 세려고 진짜에 넘기는 mock으로 감쌌다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class HighlightTitleIngestTest {

    private static final String SOLO = "Jung Hoo Lee's solo home run (7)";
    private static final String CATCH = "Jung Hoo Lee's insane underhanded catch in right";
    private static final String DOUBLE = "Ha-Seong Kim's RBI double";

    @Autowired
    private HighlightTitleRepository realTitles;

    @Autowired
    private PlayerGameDetailRepository realDetails;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private JsonMapper json;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-19T00:00:00Z"));
    private final FakeTranslator translator = new FakeTranslator();
    private HighlightTitleRepository titles;
    private PlayerGameDetailRepository details;
    private Player lee;
    private Player kim;
    private long nextGamePk = 990001;

    @BeforeEach
    void setUp() {
        // 저장된 상세가 하나도 없는 데서 시작한다(샘플 수집이 남긴 '없음' 상세를 지운다. 테스트가 끝나면 되돌아간다)
        realDetails.deleteAllInBatch();
        titles = mock(HighlightTitleRepository.class, delegatesTo(realTitles));
        details = mock(PlayerGameDetailRepository.class, delegatesTo(realDetails));
        lee = players.findByNameKo("이정후").orElseThrow();
        kim = players.findByNameKo("김하성").orElseThrow();
        assertThat(lee.getNameEn()).isNotNull();
        assertThat(kim.getNameEn()).isNotNull();
    }

    @Test
    void titlesOfSavedDetailsAreTranslatedOnceWithThePlayerOfTheirDetail() {
        // 옮기지 않는 제목: 이미 한글, 빈 제목, 없는 제목, 칸(300자)보다 긴 제목
        saveDetail(lee, SOLO, CATCH, "솔로 홈런", " ", null, "x".repeat(HighlightTitleIngest.MAX_TITLE + 1));
        saveDetail(kim, DOUBLE, SOLO);
        HighlightTitleIngest ingest = ingest();

        assertThat(ingest.translate(10)).isEqualTo("하이라이트 제목 번역 3개");

        // 나중에 저장한 상세부터 훑는다. 두 상세에 다 나온 제목은 한 번만, 먼저 훑은 상세의 선수 이름으로 옮긴다
        assertThat(translator.asked).containsExactly(DOUBLE, SOLO, CATCH);
        assertThat(translator.players).containsExactly(names(kim), names(kim), names(lee));
        assertThat(realTitles.findByTitleEn(SOLO)).hasValueSatisfying(row -> {
            assertThat(row.getTitleKo()).isEqualTo("옮긴 제목 2");
            assertThat(row.getPromptVersion()).isEqualTo("fake/t1");
        });
        assertThat(realTitles.count()).isEqualTo(3);
        assertThat(ingest.translatedCount()).isEqualTo(3);

        // 다 옮긴 뒤에는 할 일이 없다
        assertThat(ingest.translate(10)).isEmpty();
        assertThat(translator.asked).hasSize(3);
    }

    @Test
    void oneRunCallsTheTranslatorOnlyUpToTheLimit() {
        List<String> all = numbered(25);
        for (int i = 0; i < 25; i += 5) {
            saveDetail(lee, all.subList(i, i + 5).toArray(String[]::new));
        }
        HighlightTitleIngest ingest = ingest();

        assertThat(ingest.translate(10)).isEqualTo("하이라이트 제목 번역 10개");
        assertThat(translator.asked).hasSize(10);
        assertThat(ingest.translate(10)).isEqualTo("하이라이트 제목 번역 10개");
        assertThat(translator.asked).hasSize(20);
        assertThat(ingest.translate(10)).isEqualTo("하이라이트 제목 번역 5개");
        assertThat(ingest.translate(10)).isEmpty();
        assertThat(translator.asked).containsExactlyInAnyOrderElementsOf(all);
    }

    @Test
    void aTitleAlreadyTranslatedInTheCurrentEditionIsNotAskedAgain() {
        store(SOLO, "솔로 홈런 (시즌 7호)", "fake/t1");
        saveDetail(lee, SOLO, CATCH);

        assertThat(ingest().translate(10)).isEqualTo("하이라이트 제목 번역 1개");

        assertThat(translator.asked).containsExactly(CATCH);
        assertThat(realTitles.findByTitleEn(SOLO)).hasValueSatisfying(
                row -> assertThat(row.getTitleKo()).isEqualTo("솔로 홈런 (시즌 7호)"));
    }

    @Test
    void anOldEditionIsTranslatedAgainAndOverwritten() {
        store(SOLO, "옛 번역", "fake/t0");
        saveDetail(lee, SOLO);

        assertThat(ingest().translate(10)).isEqualTo("하이라이트 제목 번역 1개");

        assertThat(translator.asked).containsExactly(SOLO);
        assertThat(realTitles.count()).isEqualTo(1);
        assertThat(realTitles.findByTitleEn(SOLO)).hasValueSatisfying(row -> {
            assertThat(row.getTitleKo()).isEqualTo("옮긴 제목 1");
            assertThat(row.getPromptVersion()).isEqualTo("fake/t1");
        });
    }

    @Test
    void anUnusableAnswerRestsOnlyThatTitleUntilTheRetryTimeAndCountsTowardTheLimit() {
        saveDetail(lee, SOLO, CATCH);
        translator.unusable.add(SOLO);
        HighlightTitleIngest ingest = ingest();

        // 못 옮긴 호출도 상한에 센다: 상한 1이면 SOLO 한 번만 부르고 끝난다
        assertThat(ingest.translate(1)).isEmpty();
        assertThat(translator.asked).containsExactly(SOLO);
        // 다음 실행: SOLO는 쉬고 다른 제목은 옮긴다
        assertThat(ingest.translate(10)).isEqualTo("하이라이트 제목 번역 1개");
        assertThat(ingest.translate(10)).isEmpty();
        assertThat(translator.asked).containsExactly(SOLO, CATCH);

        clock.advance(HighlightTitleIngest.RETRY_AFTER.minusSeconds(1));
        assertThat(ingest.translate(10)).isEmpty();
        assertThat(translator.asked).containsExactly(SOLO, CATCH);

        clock.advance(Duration.ofSeconds(2));
        translator.unusable.clear();
        assertThat(ingest.translate(10)).isEqualTo("하이라이트 제목 번역 1개");
        assertThat(translator.asked).containsExactly(SOLO, CATCH, SOLO);
        assertThat(realTitles.findByTitleEn(SOLO)).isPresent();
    }

    @Test
    void whenTheServiceFailsTheRunStopsAndEveryTitlePausesThenGoesOn() {
        saveDetail(lee, SOLO, CATCH, DOUBLE);
        translator.fail = true;
        HighlightTitleIngest ingest = ingest();

        // 첫 호출이 실패하면 그 실행은 더 부르지 않는다. 예외를 밖으로 던지지 않는다(수집의 다른 단계를 멈추지 않게)
        assertThat(ingest.translate(10)).isEmpty();
        assertThat(translator.asked).hasSize(1);
        translator.fail = false;
        assertThat(ingest.translate(10)).isEmpty();
        assertThat(translator.asked).hasSize(1);

        clock.advance(HighlightTitleIngest.PAUSE.plusSeconds(1));
        assertThat(ingest.translate(10)).isEqualTo("하이라이트 제목 번역 3개");
        assertThat(translator.asked).hasSize(4);
        assertThat(realTitles.count()).isEqualTo(3);
    }

    @Test
    void withTheTranslatorOffOrInSampleModeNothingIsAskedReadOrQueued() {
        saveDetail(lee, SOLO);
        HighlightTitleIngest off = new HighlightTitleIngest(titles, details, players, HighlightTitleTranslator.OFF,
                DataMode.MLB, json, clock);
        HighlightTitleIngest sample = new HighlightTitleIngest(titles, details, players, translator, DataMode.SAMPLE,
                json, clock);

        for (HighlightTitleIngest ingest : List.of(off, sample)) {
            ingest.offer(detail(lee, 1, CATCH), lee);
            assertThat(ingest.translate(10)).isEmpty();
            assertThat(ingest.waitingCount()).isZero();
        }

        assertThat(translator.asked).isEmpty();
        verifyNoInteractions(details, titles);
        assertThat(realTitles.count()).isZero();
    }

    @Test
    void afterARestartTheBacklogGoesOnFromWhatIsAlreadySaved() {
        List<String> all = numbered(15);
        saveDetail(lee, all.subList(0, 8).toArray(String[]::new));
        saveDetail(kim, all.subList(8, 15).toArray(String[]::new));

        assertThat(ingest().translate(10)).isEqualTo("하이라이트 제목 번역 10개");
        // 서버를 다시 띄움: 메모리가 빈 새 객체가 저장된 상세를 다시 훑어 남은 5개만 옮긴다
        HighlightTitleIngest restarted = ingest();
        assertThat(restarted.waitingCount()).isZero();
        assertThat(restarted.translate(10)).isEqualTo("하이라이트 제목 번역 5개");

        assertThat(translator.asked).hasSize(15).containsExactlyInAnyOrderElementsOf(all);
        assertThat(realTitles.count()).isEqualTo(15);
        assertThat(restarted.translate(10)).isEmpty();
    }

    @Test
    void onceTheBacklogIsClearedSavedDetailsAreNotReadAgain() {
        saveDetail(lee, SOLO, CATCH);
        saveDetail(kim, DOUBLE);
        HighlightTitleIngest ingest = ingest();
        assertThat(ingest.translate(10)).isEqualTo("하이라이트 제목 번역 3개");
        assertThat(ingest.scanned()).isTrue();
        assertThat(ingest.waitingCount()).isZero();
        clearInvocations(details);
        clearInvocations(titles);

        // 그 뒤 실행은 DB를 한 번도 읽지 않는다(저장된 상세 JSON도, 번역 표도)
        for (int run = 0; run < 3; run++) {
            assertThat(ingest.translate(10)).isEmpty();
        }

        verifyNoInteractions(details, titles);
        assertThat(translator.asked).hasSize(3);
    }

    @Test
    void savedDetailsAreScannedAFewPagesPerRunUntilTheEnd() {
        // 가장 먼저 저장한(맨 끝에 훑는) 상세에만 제목이 있고, 그 뒤로 하이라이트 없는 상세가 한 번 실행 몫(200줄)보다 많다
        saveDetail(lee, SOLO);
        int perRun = HighlightTitleIngest.SCAN_PAGE * HighlightTitleIngest.SCAN_PAGES_PER_RUN;
        realDetails.saveAll(IntStream.range(0, perRun + 30).mapToObj(i -> row(kim)).toList());
        HighlightTitleIngest ingest = ingest();

        assertThat(ingest.translate(10)).isEmpty();
        assertThat(ingest.scanned()).isFalse();
        verify(details, times(HighlightTitleIngest.SCAN_PAGES_PER_RUN)).findByIdLessThanOrderByIdDesc(anyLong(), any(Limit.class));
        assertThat(translator.asked).isEmpty();

        // 다음 실행이 이어서 훑어 끝에 닿는다
        assertThat(ingest.translate(10)).isEqualTo("하이라이트 제목 번역 1개");
        assertThat(ingest.scanned()).isTrue();
        verify(details, times(HighlightTitleIngest.SCAN_PAGES_PER_RUN + 1)).findByIdLessThanOrderByIdDesc(anyLong(), any(Limit.class));
    }

    @Test
    void scanningStopsOnceThisRunsShareIsQueued() {
        // 상세 60줄(한 묶음 50줄보다 많다)에 줄마다 제목 하나: 상한 10이면 첫 묶음만 읽고 멈춘다
        List<String> all = numbered(60);
        all.forEach(title -> saveDetail(lee, title));
        HighlightTitleIngest ingest = ingest();

        assertThat(ingest.translate(10)).isEqualTo("하이라이트 제목 번역 10개");

        verify(details, times(1)).findByIdLessThanOrderByIdDesc(anyLong(), any(Limit.class));
        assertThat(ingest.scanned()).isFalse();
        assertThat(ingest.waitingCount()).isEqualTo(HighlightTitleIngest.SCAN_PAGE - 10);
    }

    @Test
    void aDetailSavedLaterIsQueuedFirstWithoutReadingSavedDetails() {
        saveDetail(lee, numbered(3).toArray(String[]::new));
        HighlightTitleIngest ingest = ingest();
        assertThat(ingest.translate(1)).isEqualTo("하이라이트 제목 번역 1개");
        assertThat(ingest.scanned()).isTrue();
        assertThat(ingest.waitingCount()).isEqualTo(2);
        clearInvocations(details);
        store(DOUBLE, "적시 2루타", "fake/t1");

        // 수집이 새 상세를 저장하며 넘긴 제목: 이미 옮긴 것(DOUBLE)은 빼고 밀린 제목보다 앞에 선다
        ingest.offer(detail(kim, 1, SOLO, DOUBLE, CATCH), kim);
        assertThat(ingest.waitingCount()).isEqualTo(4);
        assertThat(ingest.translate(2)).isEqualTo("하이라이트 제목 번역 2개");

        assertThat(translator.asked.subList(1, 3)).containsExactly(SOLO, CATCH);
        assertThat(translator.players.subList(1, 3)).containsExactly(names(kim), names(kim));
        verifyNoInteractions(details);
        // 같은 제목을 또 넘겨도 다시 줄에 서지 않는다
        ingest.offer(detail(kim, 1, SOLO, CATCH), kim);
        assertThat(ingest.waitingCount()).isEqualTo(2);
    }

    @Test
    void aSavedDetailThatCannotBeReadOrWhosePlayerHasNoEnglishNameIsSkipped() {
        saveDetail(lee, SOLO);
        PlayerGameDetail broken = new PlayerGameDetail(lee.getId(), nextGamePk++);
        broken.update("{not json", true, clock.instant());
        realDetails.save(broken);
        HighlightTitleIngest ingest = ingest();

        assertThat(ingest.translate(10)).isEqualTo("하이라이트 제목 번역 1개");
        assertThat(translator.asked).containsExactly(SOLO);
        assertThat(ingest.scanned()).isTrue();

        // 영어 이름을 모르는 선수의 제목은 줄에 넣지 않는다(번역기에 넘길 이름이 없다)
        ingest.offer(detail(lee, 1, CATCH), mock(Player.class));
        assertThat(ingest.waitingCount()).isZero();
    }

    private HighlightTitleIngest ingest() {
        return new HighlightTitleIngest(titles, details, players, translator, DataMode.MLB, json, clock);
    }

    private void saveDetail(Player player, String... highlightTitles) {
        long gamePk = nextGamePk++;
        PlayerGameDetail row = new PlayerGameDetail(player.getId(), gamePk);
        row.update(json.writeValueAsString(detail(player, gamePk, highlightTitles)), true, clock.instant());
        realDetails.save(row);
    }

    // 하이라이트가 없는 상세 한 줄
    private PlayerGameDetail row(Player player) {
        long gamePk = nextGamePk++;
        PlayerGameDetail row = new PlayerGameDetail(player.getId(), gamePk);
        row.update(json.writeValueAsString(detail(player, gamePk)), true, clock.instant());
        return row;
    }

    private static GameDetail detail(Player player, long gamePk, String... highlightTitles) {
        List<GameDetail.GameHighlight> highlights = Stream.of(highlightTitles)
                .map(title -> new GameDetail.GameHighlight(title, null, 30, null, null, null)).toList();
        return new GameDetail(player.getId(), gamePk, "HITTER", true, true, List.of(), highlights);
    }

    private void store(String titleEn, String titleKo, String version) {
        HighlightTitle row = new HighlightTitle(titleEn);
        row.translated(titleKo, version, clock.instant());
        realTitles.save(row);
    }

    private static List<String> numbered(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> "Highlight number " + i).toList();
    }

    private static String names(Player player) {
        return player.getNameKo() + "/" + player.getNameEn();
    }

    /** 부른 제목과 선수를 적어 두는 가짜 번역기. 답은 '옮긴 제목 n'(n번째 호출) */
    static class FakeTranslator implements HighlightTitleTranslator {
        final List<String> asked = new ArrayList<>();
        final List<String> players = new ArrayList<>();
        /** 빈 답(못 옮김)을 줄 제목 */
        final Set<String> unusable = new HashSet<>();
        /** AI 서비스가 답하지 못함 */
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
            return unusable.contains(titleEn) ? Optional.empty() : Optional.of("옮긴 제목 " + asked.size());
        }

        @Override
        public String version() {
            return "fake/t1";
        }
    }
}
