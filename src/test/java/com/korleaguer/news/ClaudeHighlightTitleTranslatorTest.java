package com.korleaguer.news;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 하이라이트 제목 번역 답 다듬기(API는 부르지 않는다) */
class ClaudeHighlightTitleTranslatorTest {

    @Test
    void aSingleKoreanLineIsKept() {
        assertThat(ClaudeHighlightTitleTranslator.tidy("솔로 홈런 (시즌 7호)\n")).contains("솔로 홈런 (시즌 7호)");
    }

    @Test
    void labelsQuotesAndMarkdownAreStripped() {
        assertThat(ClaudeHighlightTitleTranslator.tidy("제목: 우익수 언더핸드 호수비")).contains("우익수 언더핸드 호수비");
        assertThat(ClaudeHighlightTitleTranslator.tidy("**한국어 제목:** 레인저스전 멀티 홈런 모음")).contains("레인저스전 멀티 홈런 모음");
        assertThat(ClaudeHighlightTitleTranslator.tidy("\"우익수 호수비\"")).contains("우익수 호수비");
    }

    @Test
    void unusableAnswersAreEmptySoNothingOddIsShown() {
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
    void theVersionNamesTheModelAndThePromptEdition() {
        assertThat(new ClaudeHighlightTitleTranslator(null, "claude-haiku-5-5").version())
                .isEqualTo("claude-haiku-5-5/" + ClaudeHighlightTitleTranslator.PROMPT_VERSION);
    }

    @Test
    void aModelNameTooLongToRecordStopsTheStart() {
        assertThatThrownBy(() -> new ClaudeHighlightTitleTranslator(null, "m".repeat(48)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("too long");
    }

    @Test
    void thePromptKeepsTheRulesTheTitlesDependOn() {
        // 지시문을 고치면 이 지문도 새 값으로 고치고 PROMPT_VERSION을 올린다(옛 판 번역을 다시 옮기게)
        assertThat(ClaudeHighlightTitleTranslator.SYSTEM).contains("화면의 선수 이름만 뺀다")
                .contains("시즌 7호").contains("한국에서 흔히 쓰는 표기").contains("한 줄뿐이다");
    }
}
