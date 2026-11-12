package com.korleaguer.news;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 요약 답 다듬기(API는 부르지 않는다) */
class ClaudeSummarizerTest {

    @Test
    void theTitleLineIsSplitOffAndBlankLinesAndBulletsAreTidied() {
        assertThat(ClaudeSummarizer.tidy("제목: 한화, 노시환 MLB 포스팅\n\n- 노시환이 포스팅됐다.  \n\n협상 마감은 12월 1일이다.\n",
                false, 7, "노시환")).contains(new Summarizer.Summary("한화, 노시환 MLB 포스팅", "노시환이 포스팅됐다.\n협상 마감은 12월 1일이다."));
    }

    @Test
    void aMarkdownDecoratedTitleLineIsStillRecognised() {
        assertThat(ClaudeSummarizer.tidy("**제목:** 한화, 노시환 포스팅\n**노시환**이 포스팅됐다.", false, 7, "노시환"))
                .contains(new Summarizer.Summary("한화, 노시환 포스팅", "노시환이 포스팅됐다."));
        assertThat(ClaudeSummarizer.tidy("# 제목: 노시환 포스팅\n노시환이 포스팅됐다.", false, 7, "노시환"))
                .contains(new Summarizer.Summary("노시환 포스팅", "노시환이 포스팅됐다."));
    }

    @Test
    void withoutATitleLineEverythingIsTheSummary() {
        assertThat(ClaudeSummarizer.tidy("노시환이 포스팅됐다.", false, 7, "노시환"))
                .contains(new Summarizer.Summary(null, "노시환이 포스팅됐다."));
    }

    @Test
    void theVersionNamesTheModelAndThePromptEdition() {
        assertThat(new ClaudeSummarizer(null, "claude-haiku-5-5").version()).isEqualTo("claude-haiku-5-5/v4");
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
        // 11-12 두 번째 의견: "2문장은 너무 짧다고 그런 것만 좀 늘려달란 거였는데 전체적인 길이가 너무 길어졌어"(이슈 #111)
        assertThat(ClaudeSummarizer.SYSTEM).doesNotContain("130자").contains("'요약 분량'에 적힌 수에 맞춘다")
                .contains("그보다 적게 쓰지도 않는다").contains("기사에 없는 말을 보태지 않는다")
                .contains("총액·연봉·연평균").contains("연도로 바꾸지 않는다").contains("'기사 날짜'를 기준으로")
                .contains("이 기사가 다루는 한국 선수 이야기")
                .doesNotContain("307"); // 예시는 가상 금액(실제 선수 금액이 다른 기사 요약에 섞이지 않게)
    }

    @Test
    void anAnswerThatEchoesTheLengthInstructionIsThrownAway() {
        // 시험 319번 중 1번: 요약 대신 '요약 분량' 지시를 옮겨 썼다
        assertThat(ClaudeSummarizer.tidy("제목: 사토는 청신호인데 노시환은?\n노시환이 기사에 직접 언급된 한국 선수다. "
                + "미국 언론에서 사토만큼 관심을 받지 못하고 있다. 요약 분량 기준으로 4문장 이내로 쓴다.", false, 7, "노시환")).isEmpty();
        assertThat(ClaudeSummarizer.tidy("제목: 노시환 포스팅\n노시환은 4문장이내로 쓴다.", false, 7, "노시환")).isEmpty(); // 붙여 써도
        assertThat(ClaudeSummarizer.tidy("제목: 노시환 포스팅\n노시환은 4문장의 짧은 인터뷰를 남겼다.", false, 7, "노시환")).isPresent();
    }

    @Test
    void theSentencesAreThreeAndFourOnlyForLongArticles() {
        assertThat(ClaudeSummarizer.maxSentences("")).isEqualTo(3);
        assertThat(ClaudeSummarizer.maxSentences("가".repeat(467))).isEqualTo(3); // 짧은 기사는 예전처럼 3문장
        assertThat(ClaudeSummarizer.maxSentences("가".repeat(1116))).isEqualTo(3); // 운영 기사 중간값
        assertThat(ClaudeSummarizer.maxSentences("가".repeat(1350))).isEqualTo(3);
        assertThat(ClaudeSummarizer.maxSentences("가".repeat(1351))).isEqualTo(4); // 긴 기사만 한 문장 더
        assertThat(ClaudeSummarizer.maxSentences("가".repeat(5000))).isEqualTo(4); // 4문장이 끝
    }

    @Test
    void anEnglishBodyIsCountedAsItsKoreanLength() {
        // 같은 내용이 영어로 2.5배쯤 길다: MLB Trade Rumors 3,842자 기사는 한국어 1,537자 기사처럼 4문장
        assertThat(ClaudeSummarizer.maxSentences("Roh was posted. ".repeat(241).substring(0, 3842))).isEqualTo(4);
        assertThat(ClaudeSummarizer.maxSentences("Roh was posted. ".repeat(200).substring(0, 3000))).isEqualTo(3); // 1,200자 꼴
    }

    @Test
    void changingThePromptNeedsANewVersion() throws Exception {
        // 요약을 바꾸는 입력(지시문, 요청 틀, 분량 규칙, effort·답 길이, 본문 손질)을 바꿨으면 PROMPT_VERSION을 올리고
        // (옛 판 요약을 다시 만들게) 이 지문을 새 값으로 고친다
        String inputs = String.join("\n--\n", ClaudeSummarizer.SYSTEM, ClaudeSummarizer.USER_MESSAGE,
                ClaudeSummarizer.BODY_CHARS_PER_SENTENCE + "|" + ClaudeSummarizer.MIN_SENTENCES + "|"
                        + ClaudeSummarizer.MAX_SENTENCES + "|" + ClaudeSummarizer.ENGLISH_LENGTH_FACTOR + "|"
                        + ClaudeSummarizer.EFFORT + "|" + ClaudeSummarizer.MAX_TOKENS + "|" + ClaudeSummarizer.LENGTH_SLACK,
                // 본문 손질(영어 'last <달>'에 연도 붙이기)도 요약을 바꾼다
                ClaudeSummarizer.absoluteMonths("signed last February 14th, this past March, last November, at last May",
                        java.time.LocalDate.of(2026, 11, 2)));
        String print = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(inputs.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 16);
        assertThat(ClaudeSummarizer.PROMPT_VERSION + " " + print)
                .as("지시문·요청 틀·분량 규칙을 바꿨으면 ClaudeSummarizer.PROMPT_VERSION을 올리고 이 지문을 고친다")
                .isEqualTo("v4 886605563400827a");
    }

    @Test
    void theRequestNamesTheMostSentences() {
        assertThat(ClaudeSummarizer.userMessage("노시환", "제목", java.time.LocalDate.of(2026, 11, 2), 4, "본문"))
                .isEqualTo("이 기사가 다루는 한국 선수: 노시환\n제목: 제목\n기사 날짜: 2026-11-02\n요약 분량: 4문장\n\n본문:\n본문");
        assertThat(ClaudeSummarizer.userMessage("노시환", "제목", java.time.LocalDate.of(2026, 11, 2), 6,
                "an 11-year contract extension signed last February."))
                .endsWith("\n본문:\nan 11-year contract extension signed February 2026.");
    }

    @Test
    void anEnglishLastMonthIsSentWithTheYearOfTheMostRecentSuchMonth() {
        // 기사 날짜를 주고 지시문에 예까지 적어도, 11월 기사의 'signed last February'를 30번 중 7번 '지난해 2월'로 옮겼다(#105)
        java.time.LocalDate nov2 = java.time.LocalDate.of(2026, 11, 2);
        assertThat(ClaudeSummarizer.absoluteMonths("an extension signed last February. That extension", nov2))
                .isEqualTo("an extension signed February 2026. That extension");
        assertThat(ClaudeSummarizer.absoluteMonths("Last November he hit 30 homers.", nov2))
                .isEqualTo("November 2025 he hit 30 homers."); // 기사 달과 같은 달은 한 해 앞
        assertThat(ClaudeSummarizer.absoluteMonths("signed this past March 14th, then", nov2))
                .isEqualTo("signed March 14, 2026, then");
        assertThat(ClaudeSummarizer.absoluteMonths("traded last December 3 to", java.time.LocalDate.of(2027, 1, 15)))
                .isEqualTo("traded December 3, 2026 to");
        assertThat(ClaudeSummarizer.absoluteMonths("camp ran last March 14-16 in Arizona", nov2))
                .isEqualTo("camp ran March 14-16, 2026 in Arizona");
        // 연도가 이미 붙은 것(일·서수·줄바꿈이 끼어도), 달이 아닌 것, 'at last'(드디어), '그 달의 마지막', 한국어는 그대로
        String untouched = "last February 2025, signed last February 20, 2025, last February 1st, 2025, last February\n2025, "
                + "last season, last may be, at last May came, in his last April start, the last February day, His last May start, 지난 2월";
        assertThat(ClaudeSummarizer.absoluteMonths(untouched, nov2)).isEqualTo(untouched);
        assertThat(ClaudeSummarizer.absoluteMonths("last February", null)).isEqualTo("last February");
    }

    @Test
    void sentencesBeyondTheCountAreDroppedAndEachSentenceGetsItsOwnLine() {
        // 4문장에 6문장을 쓴 일이 있다(시험 30건 중 6건 넘김). 3~4문장으로 길이를 지키려고 넘긴 문장은 버린다(#111)
        assertThat(ClaudeSummarizer.tidy("제목: 노시환\n노시환은 첫째다. 둘째다.\n셋째다. 넷째다. 다섯째다.", false, 3, "노시환"))
                .contains(new Summarizer.Summary("노시환", "노시환은 첫째다.\n둘째다.\n셋째다."));
    }

    @Test
    void whenCuttingDropsEveryPlayerSentenceTheFirstOneTakesTheLastPlace() {
        // 사토가 주인공인 기사(시험 5번): 노시환 문장이 7번째라 잘리면 노시환 기사 페이지에 노시환이 한 번도 안 나온다
        assertThat(ClaudeSummarizer.tidy("제목: 사토\n사토는 MVP다. 사토는 3루수다. 사토는 포스팅됐다. 사토는 삼진이 많다. "
                + "사토는 1루로 옮길 것이다. 노시환도 포지션 변경 평가를 받는다.", false, 3, "노시환"))
                .contains(new Summarizer.Summary("사토", "사토는 MVP다.\n사토는 3루수다.\n"
                        + "노시환도 포지션 변경 평가를 받는다."));
    }

    @Test
    void aQuoteWithTwoSentencesStaysInOneSentence() {
        assertThat(ClaudeSummarizer.sentences("노시환은 “기회가 오면 잡겠다. 후회는 없다”고 말했다. 타율은 0.283이다."))
                .containsExactly("노시환은 “기회가 오면 잡겠다. 후회는 없다”고 말했다.", "타율은 0.283이다.");
        // 웹(JS \\s)처럼 NBSP·전각 공백 뒤에서도 나눈다
        assertThat(ClaudeSummarizer.sentences("첫째다.\u00a0둘째다.\u3000셋째다.")).containsExactly("첫째다.", "둘째다.", "셋째다.");
    }

    @Test
    void aCutOffAnswerLosesItsUnfinishedLastLine() {
        assertThat(ClaudeSummarizer.tidy("제목: 노시환 포스팅\n노시환이 포스팅됐다.\n협상 마감은 12월", true, 7, "노시환"))
                .contains(new Summarizer.Summary("노시환 포스팅", "노시환이 포스팅됐다."));
        assertThat(ClaudeSummarizer.tidy("제목: 노시환 포스팅\n노시환이 포스팅", true, 7, "노시환")).isEmpty();
    }
}
