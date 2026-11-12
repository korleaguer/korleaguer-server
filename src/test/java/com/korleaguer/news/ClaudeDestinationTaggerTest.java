package com.korleaguer.news;

import com.korleaguer.domain.NewsDestination.Stance;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 행선지 분류기의 답 읽기와 요청 글 */
class ClaudeDestinationTaggerTest {

    @Test
    void readsOneTeamPerLineWithItsStance() {
        assertThat(ClaudeDestinationTagger.parse("NYM +\nPHI +\nNYY -").orElseThrow()).containsExactly(
                new DestinationTagger.Destination("NYM", Stance.LINKED),
                new DestinationTagger.Destination("PHI", Stance.LINKED),
                new DestinationTagger.Destination("NYY", Stance.DOUBTED));
    }

    @Test
    void acceptsBulletsLowercaseOtherAbbreviationsAndTheUnicodeMinus() {
        assertThat(ClaudeDestinationTagger.parse("- sfg +\n* SDP −\n• AZ +").orElseThrow()).containsExactly(
                new DestinationTagger.Destination("SF", Stance.LINKED),
                new DestinationTagger.Destination("SD", Stance.DOUBTED),
                new DestinationTagger.Destination("ARI", Stance.LINKED));
    }

    @Test
    void ignoresAnExplanationAfterTheSign() {
        assertThat(ClaudeDestinationTagger.parse("NYM + (메츠 관심 보도)\nPHI - 필요 없다는 전망").orElseThrow()).containsExactly(
                new DestinationTagger.Destination("NYM", Stance.LINKED),
                new DestinationTagger.Destination("PHI", Stance.DOUBTED));
    }

    @Test
    void readsDecoratedAnswersSeenInProduction() {
        // 운영 첫 차례에 읽지 못한 꼴(이슈 #123): 굵은 글씨, 부호 뒤 쌍점·설명, 괄호, 번호, 한글 구단 이름
        assertThat(ClaudeDestinationTagger.parse("**NYM +**: 메츠 관심 보도\nPHI +: 김하성과 동반 영입").orElseThrow()).containsExactly(
                new DestinationTagger.Destination("NYM", Stance.LINKED),
                new DestinationTagger.Destination("PHI", Stance.LINKED));
        assertThat(ClaudeDestinationTagger.parse("1. NYM (+)\n2. `SD -`").orElseThrow()).containsExactly(
                new DestinationTagger.Destination("NYM", Stance.LINKED),
                new DestinationTagger.Destination("SD", Stance.DOUBTED));
        assertThat(ClaudeDestinationTagger.parse("뉴욕 메츠 +\n- LA 다저스 -").orElseThrow()).containsExactly(
                new DestinationTagger.Destination("NYM", Stance.LINKED),
                new DestinationTagger.Destination("LAD", Stance.DOUBTED));
    }

    @Test
    void aSentenceThatOnlyMentionsATeamIsNotRead() {
        assertThat(ClaudeDestinationTagger.parse("메츠가 관심을 보였다는 기사다")).isEmpty();
        assertThat(ClaudeDestinationTagger.parse("THE + sign")).isEmpty();
    }

    @Test
    void keepsTheFirstLineForATeamAndSkipsWhatIsNotAnMlbClub() {
        assertThat(ClaudeDestinationTagger.parse("NYM +\nNYM -\nLG +\n한화 +\n뉴욕 메츠가 관심").orElseThrow())
                .containsExactly(new DestinationTagger.Destination("NYM", Stance.LINKED));
    }

    @Test
    void noneIsAnEmptyListButAnUnreadableAnswerIsEmpty() {
        assertThat(ClaudeDestinationTagger.parse("없음")).contains(List.of());
        assertThat(ClaudeDestinationTagger.parse("없음.")).contains(List.of());
        assertThat(ClaudeDestinationTagger.parse("이 기사에는 구단이 나오지 않습니다")).isEmpty();
        assertThat(ClaudeDestinationTagger.parse("")).isEmpty();
        assertThat(ClaudeDestinationTagger.parse(null)).isEmpty();
    }

    @Test
    void theSystemPromptListsAllThirtyClubsInKorean() {
        String table = ClaudeDestinationTagger.teamTable();
        assertThat(table.lines()).hasSize(30).contains("NYM 뉴욕 메츠", "WSH 워싱턴", "ATH 애슬레틱스");
    }

    @Test
    void theRequestCarriesTitleAndTextAndSaysWhenThereIsOnlyATitle() {
        assertThat(ClaudeDestinationTagger.userMessage("노시환", "메츠행 급부상", "메츠가 관심을 보였다."))
                .isEqualTo("선수: 노시환\n제목: 메츠행 급부상\n\n기사:\n메츠가 관심을 보였다.");
        assertThat(ClaudeDestinationTagger.userMessage("노시환", "노시환 포스팅", null)).endsWith("(제목만 있다)");
        assertThat(ClaudeDestinationTagger.userMessage("노시환", "t", "가".repeat(3000)))
                .hasSize("선수: 노시환\n제목: t\n\n기사:\n".length() + ClaudeDestinationTagger.MAX_TEXT);
    }

    @Test
    void theVersionFitsTheRecordColumn() {
        assertThat(ClaudeDestinationTagger.versionOf("claude-haiku-5-5")).isEqualTo("claude-haiku-5-5/d1");
        assertThat(ClaudeDestinationTagger.fitsRecord("claude-haiku-5-5")).isTrue();
        assertThat(ClaudeDestinationTagger.fitsRecord("x".repeat(60))).isFalse();
    }
}
