package com.korleaguer.news;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 요약기 설정: 키가 없거나 모델 이름이 너무 길면 요약만 끈다(API는 뜬다) */
class NewsConfigTest {

    @Test
    @DisplayName("요약 키가 없으면 기사 요약을 끈다")
    void withoutAKeySummariesAreOff() {
        // when // then
        assertThat(new NewsConfig().summarizer("", "claude-haiku-5-5")).isSameAs(Summarizer.OFF);
    }

    @Test
    @DisplayName("요약 키와 모델을 주면 기사 요약을 켜고 요약 버전에 모델 이름을 넣는다")
    void aKeyAndAnOrdinaryModelTurnSummariesOn() {
        // when
        // 키로 요청하지는 않는다(만들기만 한다)
        Summarizer summarizer = new NewsConfig().summarizer("test-key", "claude-haiku-5-5");

        // then
        assertThat(summarizer).isInstanceOf(com.korleaguer.news.ClaudeSummarizer.class);
        assertThat(summarizer.version()).isEqualTo("claude-haiku-5-5/v5");
    }

    @Test
    @DisplayName("모델 이름이 너무 길면 기사 요약을 끈다")
    void aModelNameTooLongToRecordTurnsSummariesOffInsteadOfStoppingTheApi() {
        // when // then
        assertThat(new NewsConfig().summarizer("test-key", "m".repeat(48))).isSameAs(Summarizer.OFF);
    }
}
