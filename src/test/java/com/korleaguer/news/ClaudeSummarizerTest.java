package com.korleaguer.news;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
    void theLengthFollowsTheArticleInsteadOfAFixedCount() {
        // 사용자 의견(11-12): 쓸 내용이 많은데 지나치게 줄이지 말고, 내용이 없는데 억지로 늘리지도 말 것
        assertThat(ClaudeSummarizer.SYSTEM).doesNotContain("130자").contains("2~3문장").contains("4~7문장")
                .contains("기사에 없는 말을 보태지 않는다");
    }

    @Test
    void aCutOffAnswerLosesItsUnfinishedLastLine() {
        assertThat(ClaudeSummarizer.tidy("제목: 노시환 포스팅\n노시환이 포스팅됐다.\n협상 마감은 12월", true))
                .contains(new Summarizer.Summary("노시환 포스팅", "노시환이 포스팅됐다."));
        assertThat(ClaudeSummarizer.tidy("제목: 노시환 포스팅\n노시환이 포스팅", true)).isEmpty();
    }
}
