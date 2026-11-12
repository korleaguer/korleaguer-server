package com.korleaguer.config;

import com.korleaguer.news.Summarizer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 요약기 설정: 키가 없거나 모델 이름이 너무 길면 요약만 끈다(API는 뜬다) */
class NewsConfigTest {

    @Test
    void withoutAKeySummariesAreOff() {
        assertThat(new NewsConfig().summarizer("", "claude-haiku-5-5")).isSameAs(Summarizer.OFF);
    }

    @Test
    void aModelNameTooLongToRecordTurnsSummariesOffInsteadOfStoppingTheApi() {
        assertThat(new NewsConfig().summarizer("test-key", "m".repeat(48))).isSameAs(Summarizer.OFF);
    }
}
