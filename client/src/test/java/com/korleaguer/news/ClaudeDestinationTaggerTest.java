package com.korleaguer.news;

import com.korleaguer.domain.NewsDestination.Stance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 행선지 분류기의 답 읽기와 요청 글 */
class ClaudeDestinationTaggerTest {

    @Test
    @DisplayName("구단마다 한 줄씩 적은 답에서 거론과 부정을 나눠 읽는다")
    void readsOneTeamPerLineWithItsStance() {
        // when // then
        assertThat(ClaudeDestinationTagger.parse("NYM +\nPHI +\nNYY -").orElseThrow().destinations()).containsExactly(
                new DestinationTagger.Destination("NYM", Stance.LINKED),
                new DestinationTagger.Destination("PHI", Stance.LINKED),
                new DestinationTagger.Destination("NYY", Stance.DOUBTED));
    }

    @Test
    @DisplayName("글머리표, 소문자, 다른 약어, 다른 빼기 기호로 적은 답도 읽는다")
    void acceptsBulletsLowercaseOtherAbbreviationsAndTheUnicodeMinus() {
        // when // then
        assertThat(ClaudeDestinationTagger.parse("- sfg +\n* SDP −\n• AZ +").orElseThrow().destinations()).containsExactly(
                new DestinationTagger.Destination("SF", Stance.LINKED),
                new DestinationTagger.Destination("SD", Stance.DOUBTED),
                new DestinationTagger.Destination("ARI", Stance.LINKED));
    }

    @Test
    @DisplayName("부호 뒤에 붙은 설명은 빼고 읽는다")
    void ignoresAnExplanationAfterTheSign() {
        // when // then
        assertThat(ClaudeDestinationTagger.parse("NYM + (메츠 관심 보도)\nPHI - 필요 없다는 전망").orElseThrow().destinations()).containsExactly(
                new DestinationTagger.Destination("NYM", Stance.LINKED),
                new DestinationTagger.Destination("PHI", Stance.DOUBTED));
    }

    @Test
    @DisplayName("굵은 글씨, 괄호, 번호, 한글 구단 이름으로 적은 답도 읽는다")
    void readsDecoratedAnswersSeenInProduction() {
        // when // then
        // 운영 첫 차례에 읽지 못한 꼴(이슈 #123): 굵은 글씨, 부호 뒤 쌍점·설명, 괄호, 번호, 한글 구단 이름
        assertThat(ClaudeDestinationTagger.parse("**NYM +**: 메츠 관심 보도\nPHI +: 김하성과 동반 영입").orElseThrow().destinations()).containsExactly(
                new DestinationTagger.Destination("NYM", Stance.LINKED),
                new DestinationTagger.Destination("PHI", Stance.LINKED));
        assertThat(ClaudeDestinationTagger.parse("1. NYM (+)\n2. `SD -`").orElseThrow().destinations()).containsExactly(
                new DestinationTagger.Destination("NYM", Stance.LINKED),
                new DestinationTagger.Destination("SD", Stance.DOUBTED));
        assertThat(ClaudeDestinationTagger.parse("뉴욕 메츠 +\n- LA 다저스 -").orElseThrow().destinations()).containsExactly(
                new DestinationTagger.Destination("NYM", Stance.LINKED),
                new DestinationTagger.Destination("LAD", Stance.DOUBTED));
    }

    @Test
    @DisplayName("구단을 언급하기만 한 문장은 답으로 읽지 않는다")
    void aSentenceThatOnlyMentionsATeamIsNotRead() {
        // when // then
        assertThat(ClaudeDestinationTagger.parse("메츠가 관심을 보였다는 기사다")).isEmpty();
        assertThat(ClaudeDestinationTagger.parse("THE + sign")).isEmpty();
    }

    @Test
    @DisplayName("선수가 기사의 주인공인지 읽고 판단이 없으면 비워 둔다")
    void readsWhetherThePlayerIsTheSubject() {
        // when // then
        // 이슈 #129: 첫 줄 '주인공: 예/아니오'
        // 구단 줄 뒤 마지막 줄(#131)이어도, 앞에 있어도 읽는다
        assertThat(ClaudeDestinationTagger.parse("PHI +\n주인공: 예").orElseThrow().aboutPlayer()).isTrue();

        // when
        DestinationTagger.Tagging about = ClaudeDestinationTagger.parse("주인공: 예\nNYM +").orElseThrow();

        // then
        assertThat(about.aboutPlayer()).isTrue();
        assertThat(about.destinations()).containsExactly(new DestinationTagger.Destination("NYM", Stance.LINKED));

        // when
        DestinationTagger.Tagging kbo = ClaudeDestinationTagger.parse("**주인공: 아니오**\n없음").orElseThrow();

        // then
        assertThat(kbo.aboutPlayer()).isFalse();
        assertThat(kbo.destinations()).isEmpty();

        // when // then
        // '주인공' 줄만 있어도 읽은 답이다(거론된 구단 없음). 판단이 없으면 null
        assertThat(ClaudeDestinationTagger.parse("주인공: 아니요").orElseThrow().aboutPlayer()).isFalse();
        assertThat(ClaudeDestinationTagger.parse("PHI +").orElseThrow().aboutPlayer()).isNull();
    }

    @Test
    @DisplayName("같은 구단은 첫 줄만 쓰고 메이저 구단이 아니면 뺀다")
    void keepsTheFirstLineForATeamAndSkipsWhatIsNotAnMlbClub() {
        // when // then
        assertThat(ClaudeDestinationTagger.parse("NYM +\nNYM -\nLG +\n한화 +\n뉴욕 메츠가 관심").orElseThrow().destinations())
                .containsExactly(new DestinationTagger.Destination("NYM", Stance.LINKED));
    }

    @Test
    @DisplayName("없음이라는 답은 빈 목록으로 읽고 알아볼 수 없는 답은 버린다")
    void noneIsAnEmptyListButAnUnreadableAnswerIsEmpty() {
        // when // then
        assertThat(ClaudeDestinationTagger.parse("없음").orElseThrow().destinations()).isEmpty();
        assertThat(ClaudeDestinationTagger.parse("없음.").orElseThrow().destinations()).isEmpty();
        assertThat(ClaudeDestinationTagger.parse("이 기사에는 구단이 나오지 않습니다")).isEmpty();
        assertThat(ClaudeDestinationTagger.parse("")).isEmpty();
        assertThat(ClaudeDestinationTagger.parse(null)).isEmpty();
    }

    @Test
    @DisplayName("포스팅·MLB 선수 지시문 모두 구단 표를 넣고 선수가 주인공인지 묻는다")
    void bothPromptsTakeTheTeamTableAndAskWhetherThePlayerIsTheSubject() {
        // when // then
        // 이슈 #139: MLB 선수 지시문도 같은 답 모양(구단 줄 + 마지막 줄 '주인공')을 쓴다
        for (String prompt : List.of(ClaudeDestinationTagger.SYSTEM, ClaudeDestinationTagger.SYSTEM_MLB)) {
            assertThat(prompt).contains("%s", "'주인공: 예'", "'주인공: 아니오'", "'약어 +'", "'약어 -'", "'없음'");
        }
    }

    @Test
    @DisplayName("구단 표는 서른 줄이고 한글 구단 이름이 적혀 있다")
    void theSystemPromptListsAllThirtyClubsInKorean() {
        // when
        String table = ClaudeDestinationTagger.teamTable();

        // then
        assertThat(table.lines()).hasSize(30).contains("NYM 뉴욕 메츠", "WSH 워싱턴", "ATH 애슬레틱스");
    }

    @Test
    @DisplayName("요청에 제목과 본문을 한도까지 넣고 본문이 없으면 제목만 있다고 적는다")
    void theRequestCarriesTitleAndTextAndSaysWhenThereIsOnlyATitle() {
        // when // then
        assertThat(ClaudeDestinationTagger.userMessage("노시환", "메츠행 급부상", "메츠가 관심을 보였다."))
                .isEqualTo("선수: 노시환\n제목: 메츠행 급부상\n\n기사:\n메츠가 관심을 보였다.");
        assertThat(ClaudeDestinationTagger.userMessage("노시환", "노시환 포스팅", null)).endsWith("(제목만 있다)");
        assertThat(ClaudeDestinationTagger.userMessage("노시환", "t", "가".repeat(3000)))
                .hasSize("선수: 노시환\n제목: t\n\n기사:\n".length() + ClaudeDestinationTagger.MAX_TEXT);
    }

    @Test
    @DisplayName("분류 버전에 모델과 종류별 지시문 버전을 적고 너무 긴 모델 이름은 거른다")
    void theVersionFitsTheRecordColumn() {
        // when // then
        assertThat(ClaudeDestinationTagger.versionOf("claude-haiku-5-5", DestinationTagger.Kind.POSTING))
                .isEqualTo("claude-haiku-5-5/d3");
        assertThat(ClaudeDestinationTagger.versionOf("claude-haiku-5-5", DestinationTagger.Kind.MLB))
                .isEqualTo("claude-haiku-5-5/m1");
        assertThat(ClaudeDestinationTagger.fitsRecord("claude-haiku-5-5")).isTrue();
        assertThat(ClaudeDestinationTagger.fitsRecord("x".repeat(60))).isFalse();
    }
}
