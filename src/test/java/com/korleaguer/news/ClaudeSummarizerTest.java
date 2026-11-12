package com.korleaguer.news;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 요약 답 다듬기(API는 부르지 않는다) */
class ClaudeSummarizerTest {

    @Test
    void theTitleLineIsSplitOffAndBlankLinesAndBulletsAreTidied() {
        assertThat(ClaudeSummarizer.tidy("제목: 한화, 노시환 MLB 포스팅\n\n- 노시환이 포스팅됐다.  \n\n협상 마감은 12월 1일이다.\n",
                false)).contains(new Summarizer.Summary("한화, 노시환 MLB 포스팅", "노시환이 포스팅됐다.\n협상 마감은 12월 1일이다."));
    }

    @Test
    void aMarkdownDecoratedTitleLineIsStillRecognised() {
        assertThat(ClaudeSummarizer.tidy("**제목:** 한화, 노시환 포스팅\n**노시환**이 포스팅됐다.", false))
                .contains(new Summarizer.Summary("한화, 노시환 포스팅", "노시환이 포스팅됐다."));
        assertThat(ClaudeSummarizer.tidy("# 제목: 노시환 포스팅\n노시환이 포스팅됐다.", false))
                .contains(new Summarizer.Summary("노시환 포스팅", "노시환이 포스팅됐다."));
    }

    @Test
    void withoutATitleLineEverythingIsTheSummary() {
        assertThat(ClaudeSummarizer.tidy("노시환이 포스팅됐다.", false))
                .contains(new Summarizer.Summary(null, "노시환이 포스팅됐다."));
    }

    @Test
    void theVersionNamesTheModelAndThePromptEdition() {
        assertThat(new ClaudeSummarizer(null, "claude-haiku-5-5").version()).isEqualTo("claude-haiku-5-5/v2");
    }

    @Test
    void aModelNameTooLongToRecordStopsTheStart() {
        // summary_model 칸(50자)에 들어가지 않으면 저장할 때마다 실패하고 같은 기사를 계속 다시 요약하게 된다
        assertThatThrownBy(() -> new ClaudeSummarizer(null, "m".repeat(48)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("too long");
    }

    @Test
    void theLengthFollowsTheArticleInsteadOfAFixedCount() {
        // 사용자 의견(11-12): 쓸 내용이 많은데 지나치게 줄이지 말고, 내용이 없는데 억지로 늘리지도 말 것
        assertThat(ClaudeSummarizer.SYSTEM).doesNotContain("130자").contains("'요약 분량'")
                .contains("쓸 사실이 떨어지면 거기서 멈춘다").contains("기사에 없는 말을 보태지 않는다")
                .contains("총액·연봉·연평균");
    }

    @Test
    void theMostSentencesGrowWithTheBodyBetweenThreeAndSeven() {
        assertThat(ClaudeSummarizer.maxSentences("")).isEqualTo(3);
        assertThat(ClaudeSummarizer.maxSentences("가".repeat(467))).isEqualTo(3); // 짧은 기사는 지금처럼 3문장
        assertThat(ClaudeSummarizer.maxSentences("가".repeat(1116))).isEqualTo(4); // 운영 기사 중간값
        assertThat(ClaudeSummarizer.maxSentences("가".repeat(1879))).isEqualTo(7);
        assertThat(ClaudeSummarizer.maxSentences("가".repeat(5000))).isEqualTo(7); // 7문장이 끝
    }

    @Test
    void anEnglishBodyIsCountedAsItsKoreanLength() {
        // 같은 내용이 영어로 2.5배쯤 길다: MLB Trade Rumors 3,842자 기사는 한국어 1,537자 기사처럼 6문장
        assertThat(ClaudeSummarizer.maxSentences("Roh was posted. ".repeat(241).substring(0, 3842))).isEqualTo(6);
    }

    @Test
    void changingThePromptNeedsANewVersion() throws Exception {
        // 지시문(SYSTEM)을 바꿨으면 PROMPT_VERSION을 올리고(옛 판 요약을 다시 만들게) 이 지문을 새 값으로 고친다
        String print = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(ClaudeSummarizer.SYSTEM.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 16);
        assertThat(ClaudeSummarizer.PROMPT_VERSION + " " + print).isEqualTo("v2 175dc1e8ef37b4c2");
    }

    @Test
    void aCutOffAnswerLosesItsUnfinishedLastLine() {
        assertThat(ClaudeSummarizer.tidy("제목: 노시환 포스팅\n노시환이 포스팅됐다.\n협상 마감은 12월", true))
                .contains(new Summarizer.Summary("노시환 포스팅", "노시환이 포스팅됐다."));
        assertThat(ClaudeSummarizer.tidy("제목: 노시환 포스팅\n노시환이 포스팅", true)).isEmpty();
    }
}
