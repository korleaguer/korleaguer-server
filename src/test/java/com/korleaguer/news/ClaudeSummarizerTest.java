package com.korleaguer.news;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 요약 답 다듬기(API는 부르지 않는다) */
class ClaudeSummarizerTest {

    @Test
    void blankLinesAndSpacesAreTidied() {
        assertThat(ClaudeSummarizer.tidy("  노시환이 포스팅됐다.  \n\n\n협상 마감은 12월 1일이다.\n", false))
                .contains("노시환이 포스팅됐다.\n협상 마감은 12월 1일이다.");
    }

    @Test
    void aCutOffAnswerLosesItsUnfinishedLastLine() {
        assertThat(ClaudeSummarizer.tidy("노시환이 포스팅됐다.\n협상 마감은 12월", true)).contains("노시환이 포스팅됐다.");
        assertThat(ClaudeSummarizer.tidy("노시환이 포스팅", true)).isEmpty();
    }
}
