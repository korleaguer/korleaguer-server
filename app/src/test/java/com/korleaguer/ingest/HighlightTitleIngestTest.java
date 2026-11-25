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
import com.korleaguer.support.CommittedDbTest;
import com.korleaguer.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
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
@CommittedDbTest
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
        // 저장된 상세와 번역이 하나도 없는 데서 시작한다(샘플 수집이 남긴 '없음' 상세와 앞 테스트가 저장한 것을 지운다)
        realDetails.deleteAllInBatch();
        realTitles.deleteAllInBatch();
        titles = mock(HighlightTitleRepository.class, delegatesTo(realTitles));
        details = mock(PlayerGameDetailRepository.class, delegatesTo(realDetails));
        lee = players.findByNameKo("이정후").orElseThrow();
        kim = players.findByNameKo("김하성").orElseThrow();
        assertThat(lee.getNameEn()).isNotNull();
        assertThat(kim.getNameEn()).isNotNull();
    }

    @Test
    @DisplayName("저장된 영상 제목을 그 경기의 선수 이름과 함께 넘겨 한 번씩만 번역한다")
    void titlesOfSavedDetailsAreTranslatedOnceWithThePlayerOfTheirDetail() {
        // given
        // 옮기지 않는 제목: 이미 한글, 빈 제목, 없는 제목, 칸(300자)보다 긴 제목
        saveDetail(lee, SOLO, CATCH, "솔로 홈런", " ", null, "x".repeat(HighlightTitleIngest.MAX_TITLE + 1));
        saveDetail(kim, DOUBLE, SOLO);
        HighlightTitleIngest ingest = ingest();

        // when // then
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

        // when // then
        // 다 옮긴 뒤에는 할 일이 없다
        assertThat(ingest.translate(10)).isEmpty();
        assertThat(translator.asked).hasSize(3);
    }

    @Test
    @DisplayName("제목을 한 번에 10개씩만 번역하고 남은 것은 다음번에 이어서 번역한다")
    void oneRunCallsTheTranslatorOnlyUpToTheLimit() {
        // given
        List<String> all = numbered(25);
        for (int i = 0; i < 25; i += 5) {
            saveDetail(lee, all.subList(i, i + 5).toArray(String[]::new));
        }
        HighlightTitleIngest ingest = ingest();

        // when // then
        assertThat(ingest.translate(10)).isEqualTo("하이라이트 제목 번역 10개");
        assertThat(translator.asked).hasSize(10);
        assertThat(ingest.translate(10)).isEqualTo("하이라이트 제목 번역 10개");
        assertThat(translator.asked).hasSize(20);
        assertThat(ingest.translate(10)).isEqualTo("하이라이트 제목 번역 5개");
        assertThat(ingest.translate(10)).isEmpty();
        assertThat(translator.asked).containsExactlyInAnyOrderElementsOf(all);
    }

    @Test
    @DisplayName("지금 규칙으로 번역한 제목은 다시 묻지 않고 그대로 둔다")
    void aTitleAlreadyTranslatedInTheCurrentEditionIsNotAskedAgain() {
        // given
        store(SOLO, "솔로 홈런 (시즌 7호)", "fake/t1");
        saveDetail(lee, SOLO, CATCH);

        // when // then
        assertThat(ingest().translate(10)).isEqualTo("하이라이트 제목 번역 1개");

        assertThat(translator.asked).containsExactly(CATCH);
        assertThat(realTitles.findByTitleEn(SOLO)).hasValueSatisfying(
                row -> assertThat(row.getTitleKo()).isEqualTo("솔로 홈런 (시즌 7호)"));
    }

    @Test
    @DisplayName("옛 규칙으로 번역한 제목은 다시 번역해 덮어쓴다")
    void anOldEditionIsTranslatedAgainAndOverwritten() {
        // given
        store(SOLO, "옛 번역", "fake/t0");
        saveDetail(lee, SOLO);

        // when // then
        assertThat(ingest().translate(10)).isEqualTo("하이라이트 제목 번역 1개");

        assertThat(translator.asked).containsExactly(SOLO);
        assertThat(realTitles.count()).isEqualTo(1);
        assertThat(realTitles.findByTitleEn(SOLO)).hasValueSatisfying(row -> {
            assertThat(row.getTitleKo()).isEqualTo("옮긴 제목 1");
            assertThat(row.getPromptVersion()).isEqualTo("fake/t1");
        });
    }

    @Test
    @DisplayName("번역에 실패한 호출도 횟수 상한에 넣고 그 제목만 쉬었다가 다시 번역한다")
    void anUnusableAnswerRestsOnlyThatTitleUntilTheRetryTimeAndCountsTowardTheLimit() {
        // given
        saveDetail(lee, SOLO, CATCH);
        translator.unusable.add(SOLO);
        HighlightTitleIngest ingest = ingest();

        // when // then
        // 못 옮긴 호출도 상한에 센다: 상한 1이면 SOLO 한 번만 부르고 끝난다
        assertThat(ingest.translate(1)).isEmpty();
        assertThat(translator.asked).containsExactly(SOLO);
        // 다음 실행: SOLO는 쉬고 다른 제목은 옮긴다
        assertThat(ingest.translate(10)).isEqualTo("하이라이트 제목 번역 1개");
        assertThat(ingest.translate(10)).isEmpty();
        assertThat(translator.asked).containsExactly(SOLO, CATCH);

        // given
        clock.advance(HighlightTitleIngest.RETRY_AFTER.minusSeconds(1));

        // when // then
        assertThat(ingest.translate(10)).isEmpty();
        assertThat(translator.asked).containsExactly(SOLO, CATCH);

        // given
        clock.advance(Duration.ofSeconds(2));
        translator.unusable.clear();

        // when // then
        assertThat(ingest.translate(10)).isEqualTo("하이라이트 제목 번역 1개");
        assertThat(translator.asked).containsExactly(SOLO, CATCH, SOLO);
        assertThat(realTitles.findByTitleEn(SOLO)).isPresent();
    }

    @Test
    @DisplayName("계속 번역하지 못한 제목은 쉬는 시간을 두 배씩 늘리되 하루를 넘기지 않는다")
    void aTitleThatKeepsFailingRestsLongerEachTimeUpToADay() {
        // given
        saveDetail(lee, SOLO);
        translator.unusable.add(SOLO);
        HighlightTitleIngest ingest = ingest();

        // when // then
        // 1시간 → 2시간 → 4시간 … 쉬는 시간이 두 배씩 는다
        ingest.translate(10);
        for (int hours : new int[]{1, 2, 4, 8, 16}) {
            int before = translator.asked.size();
            clock.advance(Duration.ofHours(hours).minusSeconds(1));
            ingest.translate(10);
            assertThat(translator.asked).hasSize(before);
            clock.advance(Duration.ofSeconds(1));
            ingest.translate(10);
            assertThat(translator.asked).hasSize(before + 1);
        }

        // when // then
        // 그 뒤로는 하루(MAX_RETRY_AFTER)에 한 번만 다시 해 본다
        for (int day = 0; day < 3; day++) {
            int before = translator.asked.size();
            clock.advance(HighlightTitleIngest.MAX_RETRY_AFTER.minusSeconds(1));
            ingest.translate(10);
            assertThat(translator.asked).hasSize(before);
            clock.advance(Duration.ofSeconds(1));
            ingest.translate(10);
            assertThat(translator.asked).hasSize(before + 1);
        }
        assertThat(translator.asked).containsOnly(SOLO);
    }

    @Test
    @DisplayName("번역기가 답하지 않으면 바로 멈추고 모든 제목을 쉬었다가 이어서 번역한다")
    void whenTheServiceFailsTheRunStopsAndEveryTitlePausesThenGoesOn() {
        // given
        saveDetail(lee, SOLO, CATCH, DOUBLE);
        translator.fail = true;
        HighlightTitleIngest ingest = ingest();

        // when // then
        // 첫 호출이 실패하면 그 실행은 더 부르지 않는다. 예외를 밖으로 던지지 않는다(수집의 다른 단계를 멈추지 않게)
        assertThat(ingest.translate(10)).isEmpty();
        assertThat(translator.asked).hasSize(1);

        // given
        translator.fail = false;

        // when // then
        assertThat(ingest.translate(10)).isEmpty();
        assertThat(translator.asked).hasSize(1);

        // given
        clock.advance(HighlightTitleIngest.PAUSE.plusSeconds(1));

        // when // then
        assertThat(ingest.translate(10)).isEqualTo("하이라이트 제목 번역 3개");
        assertThat(translator.asked).hasSize(4);
        assertThat(realTitles.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("번역기를 끄거나 샘플 모드면 제목을 번역하지도 대기 줄에 넣지도 않는다")
    void withTheTranslatorOffOrInSampleModeNothingIsAskedReadOrQueued() {
        // given
        saveDetail(lee, SOLO);
        HighlightTitleIngest off = new HighlightTitleIngest(titles, details, players, HighlightTitleTranslator.OFF,
                DataMode.MLB, json, clock);
        HighlightTitleIngest sample = new HighlightTitleIngest(titles, details, players, translator, DataMode.SAMPLE,
                json, clock);

        // when // then
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
    @DisplayName("서버를 다시 띄우면 저장된 경기에서 남은 제목만 이어서 번역한다")
    void afterARestartTheBacklogGoesOnFromWhatIsAlreadySaved() {
        // given
        List<String> all = numbered(15);
        saveDetail(lee, all.subList(0, 8).toArray(String[]::new));
        saveDetail(kim, all.subList(8, 15).toArray(String[]::new));

        // when // then
        assertThat(ingest().translate(10)).isEqualTo("하이라이트 제목 번역 10개");

        // given
        // 서버를 다시 띄움: 메모리가 빈 새 객체가 저장된 상세를 다시 훑어 남은 5개만 옮긴다
        HighlightTitleIngest restarted = ingest();

        // when // then
        assertThat(restarted.waitingCount()).isZero();
        assertThat(restarted.translate(10)).isEqualTo("하이라이트 제목 번역 5개");

        assertThat(translator.asked).hasSize(15).containsExactlyInAnyOrderElementsOf(all);
        assertThat(realTitles.count()).isEqualTo(15);
        assertThat(restarted.translate(10)).isEmpty();
    }

    @Test
    @DisplayName("밀린 제목을 다 번역하면 저장된 경기와 번역을 다시 읽지 않는다")
    void onceTheBacklogIsClearedSavedDetailsAreNotReadAgain() {
        // given
        saveDetail(lee, SOLO, CATCH);
        saveDetail(kim, DOUBLE);
        HighlightTitleIngest ingest = ingest();
        assertThat(ingest.translate(10)).isEqualTo("하이라이트 제목 번역 3개");
        assertThat(ingest.scanned()).isTrue();
        assertThat(ingest.waitingCount()).isZero();
        clearInvocations(details);
        clearInvocations(titles);

        // when // then
        // 그 뒤 실행은 DB를 한 번도 읽지 않는다(저장된 상세 JSON도, 번역 표도)
        for (int run = 0; run < 3; run++) {
            assertThat(ingest.translate(10)).isEmpty();
        }

        verifyNoInteractions(details, titles);
        assertThat(translator.asked).hasSize(3);
    }

    @Test
    @DisplayName("저장된 경기를 한 번에 몇 묶음씩만 읽고 다음번에 이어서 끝까지 읽는다")
    void savedDetailsAreScannedAFewPagesPerRunUntilTheEnd() {
        // given
        // 가장 먼저 저장한(맨 끝에 훑는) 상세에만 제목이 있고, 그 뒤로 하이라이트 없는 상세가 한 번 실행 몫(200줄)보다 많다
        saveDetail(lee, SOLO);
        int perRun = HighlightTitleIngest.SCAN_PAGE * HighlightTitleIngest.SCAN_PAGES_PER_RUN;
        realDetails.saveAll(IntStream.range(0, perRun + 30).mapToObj(i -> row(kim)).toList());
        HighlightTitleIngest ingest = ingest();

        // when // then
        assertThat(ingest.translate(10)).isEmpty();
        assertThat(ingest.scanned()).isFalse();
        verify(details, times(HighlightTitleIngest.SCAN_PAGES_PER_RUN)).findByIdLessThanOrderByIdDesc(anyLong(), any(Limit.class));
        assertThat(translator.asked).isEmpty();

        // when // then
        // 다음 실행이 이어서 훑어 끝에 닿는다
        assertThat(ingest.translate(10)).isEqualTo("하이라이트 제목 번역 1개");
        assertThat(ingest.scanned()).isTrue();
        verify(details, times(HighlightTitleIngest.SCAN_PAGES_PER_RUN + 1)).findByIdLessThanOrderByIdDesc(anyLong(), any(Limit.class));
    }

    @Test
    @DisplayName("이번에 번역할 제목이 차면 저장된 경기를 더 읽지 않는다")
    void scanningStopsOnceThisRunsShareIsQueued() {
        // given
        // 상세 60줄(한 묶음 50줄보다 많다)에 줄마다 제목 하나: 상한 10이면 첫 묶음만 읽고 멈춘다
        List<String> all = numbered(60);
        all.forEach(title -> saveDetail(lee, title));
        HighlightTitleIngest ingest = ingest();

        // when // then
        assertThat(ingest.translate(10)).isEqualTo("하이라이트 제목 번역 10개");

        verify(details, times(1)).findByIdLessThanOrderByIdDesc(anyLong(), any(Limit.class));
        assertThat(ingest.scanned()).isFalse();
        assertThat(ingest.waitingCount()).isEqualTo(HighlightTitleIngest.SCAN_PAGE - 10);
    }

    @Test
    @DisplayName("새로 받은 경기의 제목은 밀린 제목보다 먼저 번역하고 번역된 것은 뺀다")
    void aDetailSavedLaterIsQueuedFirstWithoutReadingSavedDetails() {
        // given
        saveDetail(lee, numbered(3).toArray(String[]::new));
        HighlightTitleIngest ingest = ingest();
        assertThat(ingest.translate(1)).isEqualTo("하이라이트 제목 번역 1개");
        assertThat(ingest.scanned()).isTrue();
        assertThat(ingest.waitingCount()).isEqualTo(2);
        clearInvocations(details);
        store(DOUBLE, "적시 2루타", "fake/t1");

        // when
        // 수집이 새 상세를 저장하며 넘긴 제목: 이미 옮긴 것(DOUBLE)은 빼고 밀린 제목보다 앞에 선다
        ingest.offer(detail(kim, 1, SOLO, DOUBLE, CATCH), kim);

        // then
        assertThat(ingest.waitingCount()).isEqualTo(4);

        // when // then
        assertThat(ingest.translate(2)).isEqualTo("하이라이트 제목 번역 2개");

        assertThat(translator.asked.subList(1, 3)).containsExactly(SOLO, CATCH);
        assertThat(translator.players.subList(1, 3)).containsExactly(names(kim), names(kim));
        verifyNoInteractions(details);

        // when
        // 같은 제목을 또 넘겨도 다시 줄에 서지 않는다
        ingest.offer(detail(kim, 1, SOLO, CATCH), kim);

        // then
        assertThat(ingest.waitingCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("읽을 수 없는 경기 기록과 영어 이름을 모르는 선수의 제목은 건너뛴다")
    void aSavedDetailThatCannotBeReadOrWhosePlayerHasNoEnglishNameIsSkipped() {
        // given
        saveDetail(lee, SOLO);
        PlayerGameDetail broken = new PlayerGameDetail(lee.getId(), nextGamePk++);
        broken.update("{not json", true, clock.instant());
        realDetails.save(broken);
        HighlightTitleIngest ingest = ingest();

        // when // then
        assertThat(ingest.translate(10)).isEqualTo("하이라이트 제목 번역 1개");
        assertThat(translator.asked).containsExactly(SOLO);
        assertThat(ingest.scanned()).isTrue();

        // when
        // 영어 이름을 모르는 선수의 제목은 줄에 넣지 않는다(번역기에 넘길 이름이 없다)
        ingest.offer(detail(lee, 1, CATCH), mock(Player.class));

        // then
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
