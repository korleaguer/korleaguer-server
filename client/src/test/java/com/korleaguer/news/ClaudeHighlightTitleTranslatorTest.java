package com.korleaguer.news;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 하이라이트 제목 번역 답 다듬기(API는 부르지 않는다) */
class ClaudeHighlightTitleTranslatorTest {

    @Test
    @DisplayName("한국어 한 줄로 온 답은 제목으로 쓴다")
    void aSingleKoreanLineIsKept() {
        // when // then
        assertThat(ClaudeHighlightTitleTranslator.tidy("솔로 홈런 (시즌 7호)\n")).contains("솔로 홈런 (시즌 7호)");
    }

    @Test
    @DisplayName("제목에 붙은 이름표와 따옴표와 굵은 글씨 표시는 뗀다")
    void labelsQuotesAndMarkdownAreStripped() {
        // when // then
        assertThat(ClaudeHighlightTitleTranslator.tidy("제목: 우익수 언더핸드 호수비")).contains("우익수 언더핸드 호수비");
        assertThat(ClaudeHighlightTitleTranslator.tidy("**한국어 제목:** 레인저스전 멀티 홈런 모음")).contains("레인저스전 멀티 홈런 모음");
        assertThat(ClaudeHighlightTitleTranslator.tidy("\"우익수 호수비\"")).contains("우익수 호수비");
    }

    @Test
    @DisplayName("한글이 없거나 설명이 섞였거나 비었거나 너무 긴 번역은 버린다")
    void unusableAnswersAreEmptySoNothingOddIsShown() {
        // when // then
        // 한글이 없다(못 옮김), 여러 줄(설명을 덧붙임), 빈 답, 칸보다 긺
        assertThat(ClaudeHighlightTitleTranslator.tidy("solo home run")).isEmpty();
        // 제목 대신 이름을 뺀 이유나 생각을 적은 답
        assertThat(ClaudeHighlightTitleTranslator.tidy("김혜성 도루 — no wait, 2루 도루")).isEmpty();
        assertThat(ClaudeHighlightTitleTranslator.tidy("김하성 선수 이름을 빼고 옮기면: 점프 캐치")).isEmpty();
        assertThat(ClaudeHighlightTitleTranslator.tidy("솔로 홈런\n영어 제목을 옮겼습니다")).isEmpty();
        assertThat(ClaudeHighlightTitleTranslator.tidy("  ")).isEmpty();
        assertThat(ClaudeHighlightTitleTranslator.tidy("가".repeat(ClaudeHighlightTitleTranslator.MAX_TITLE + 1))).isEmpty();
    }

    @Test
    @DisplayName("번역 버전에 모델 이름과 지시문 버전을 적는다")
    void theVersionNamesTheModelAndThePromptEdition() {
        // when // then
        assertThat(new ClaudeHighlightTitleTranslator(null, "claude-haiku-5-5").version())
                .isEqualTo("claude-haiku-5-5/" + ClaudeHighlightTitleTranslator.PROMPT_VERSION);
    }

    @Test
    @DisplayName("모델 이름이 너무 길면 번역기를 만들지 않는다")
    void aModelNameTooLongToRecordStopsTheStart() {
        // when // then
        assertThatThrownBy(() -> new ClaudeHighlightTitleTranslator(null, "m".repeat(48)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("too long");
    }

    @Test
    @DisplayName("번역 지시문에 선수 이름 빼기와 표기, 한 줄 규칙이 들어 있다")
    void thePromptKeepsTheRulesTheTitlesDependOn() {
        // when // then
        // 지시문을 고치면 이 지문도 새 값으로 고치고 PROMPT_VERSION을 올린다(옛 판 번역을 다시 옮기게)
        assertThat(ClaudeHighlightTitleTranslator.SYSTEM).contains("화면의 선수 이름만 뺀다")
                .contains("시즌 7호").contains("한국에서 흔히 쓰는 표기").contains("한 줄뿐이다");
    }
}
