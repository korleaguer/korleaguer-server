package com.korleaguer.news;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.Headers;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Claude를 부르는 세 기능(기사 요약, 행선지 구단 고르기, 하이라이트 제목 번역)이 API의 답과 오류를 다루는 법.
 * API는 부르지 않고 가짜 클라이언트가 답한다.
 */
class ClaudeCallsTest {

    /** 세 기능을 같은 모양으로 부르기 위한 틀 */
    enum Feature {
        SUMMARY("제목: 노시환 포스팅\n노시환이 포스팅됐다.") {
            @Override
            Feature.Caller caller(AnthropicClient client) {
                ClaudeSummarizer summarizer = new ClaudeSummarizer(client, "claude-haiku-5-5");
                return new Caller(summarizer::enabled,
                        () -> summarizer.summarize("노시환", "노시환 포스팅", LocalDate.of(2026, 11, 20), "노시환이 포스팅됐다."));
            }
        },
        DESTINATION("NYM +") {
            @Override
            Feature.Caller caller(AnthropicClient client) {
                ClaudeDestinationTagger tagger = new ClaudeDestinationTagger(client, "claude-haiku-5-5");
                return new Caller(tagger::enabled,
                        () -> tagger.tag("노시환", DestinationTagger.Kind.POSTING, "노시환 메츠행?", "메츠가 관심을 보였다."));
            }
        },
        HIGHLIGHT_TITLE("이정후의 2루타") {
            @Override
            Feature.Caller caller(AnthropicClient client) {
                ClaudeHighlightTitleTranslator translator = new ClaudeHighlightTitleTranslator(client, "claude-haiku-5-5");
                return new Caller(translator::enabled,
                        () -> translator.translate("Jung Hoo Lee's double", "이정후", "Jung Hoo Lee"));
            }
        };

        /** 이 기능이 받아들이는 정상 답 */
        final String goodAnswer;

        Feature(String goodAnswer) {
            this.goodAnswer = goodAnswer;
        }

        abstract Caller caller(AnthropicClient client);

        record Caller(java.util.function.BooleanSupplier enabled, java.util.function.Supplier<Optional<?>> call) {
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Feature.class)
    @DisplayName("API 키가 거절되면(401) 실패로 넘기고 서버를 다시 띄울 때까지 그 기능을 끈다")
    void aRejectedKeyFailsAndTurnsTheFeatureOff(Feature feature) {
        // given
        Feature.Caller caller = feature.caller(failingWith(UnauthorizedException.builder()
                .headers(Headers.builder().build()).body(JsonValue.from(Map.of())).build()));

        // when // then
        assertThatThrownBy(() -> caller.call().get()).isInstanceOf(Summarizer.Failure.class)
                .hasMessageStartingWith("UnauthorizedException");
        assertThat(caller.enabled().getAsBoolean()).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Feature.class)
    @DisplayName("권한이 없다는 답(403)도 키 거절로 보고 그 기능을 끈다")
    void aForbiddenKeyAlsoTurnsTheFeatureOff(Feature feature) {
        // given
        Feature.Caller caller = feature.caller(failingWith(PermissionDeniedException.builder()
                .headers(Headers.builder().build()).body(JsonValue.from(Map.of())).build()));

        // when // then
        assertThatThrownBy(() -> caller.call().get()).isInstanceOf(Summarizer.Failure.class);
        assertThat(caller.enabled().getAsBoolean()).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Feature.class)
    @DisplayName("사용량 제한(429) 같은 다른 오류는 실패로 넘기되 기능을 끄지 않는다")
    void otherErrorsFailButKeepTheFeatureOn(Feature feature) {
        // given
        Feature.Caller caller = feature.caller(failingWith(RateLimitException.builder()
                .headers(Headers.builder().build()).body(JsonValue.from(Map.of())).build()));

        // when // then
        assertThatThrownBy(() -> caller.call().get()).isInstanceOf(Summarizer.Failure.class)
                .hasMessageStartingWith("RateLimitException");
        assertThat(caller.enabled().getAsBoolean()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Feature.class)
    @DisplayName("정상 답은 결과가 있고, 모델이 답을 거절하면 결과가 없다")
    void aGoodAnswerHasAResultAndARefusalHasNone(Feature feature) {
        // given
        Feature.Caller good = feature.caller(answering(StopReason.END_TURN, feature.goodAnswer));
        Feature.Caller refused = feature.caller(answering(StopReason.REFUSAL, feature.goodAnswer));

        // when // then
        assertThat(good.call().get()).isPresent();
        assertThat(refused.call().get()).isEmpty();
        assertThat(refused.enabled().getAsBoolean()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Feature.class, names = {"DESTINATION", "HIGHLIGHT_TITLE"})
    @DisplayName("행선지와 제목 번역은 길이 제한에 잘린 답을 버린다")
    void aCutOffAnswerIsDroppedForTagsAndTitles(Feature feature) {
        // given
        Feature.Caller caller = feature.caller(answering(StopReason.MAX_TOKENS, feature.goodAnswer));

        // when // then
        assertThat(caller.call().get()).isEmpty();
    }

    @org.junit.jupiter.api.Test
    @DisplayName("요약은 길이 제한에 잘린 답의 마지막 줄만 버리고 쓰며, 여러 글 조각을 줄을 바꿔 잇는다")
    void aCutOffSummaryKeepsAllButTheLastLine() {
        // given
        ClaudeSummarizer summarizer = new ClaudeSummarizer(
                answering(StopReason.MAX_TOKENS, "제목: 노시환 포스팅", "노시환이 포스팅됐다.", "협상 마감은"), "claude-haiku-5-5");

        // when
        Optional<Summarizer.Summary> summary = summarizer.summarize("노시환", "노시환 포스팅", LocalDate.of(2026, 11, 20),
                "노시환이 포스팅됐다.");

        // then
        assertThat(summary).contains(new Summarizer.Summary("노시환 포스팅", "노시환이 포스팅됐다."));
    }

    private static AnthropicClient failingWith(AnthropicException error) {
        AnthropicClient client = mock(AnthropicClient.class, RETURNS_DEEP_STUBS);
        when(client.messages().create(any(MessageCreateParams.class))).thenThrow(error);
        return client;
    }

    /** 글 조각(text 블록)마다 하나씩 담은 답 */
    private static AnthropicClient answering(StopReason stop, String... texts) {
        List<ContentBlock> blocks = Arrays.stream(texts).map(text -> {
            TextBlock textBlock = mock(TextBlock.class);
            when(textBlock.text()).thenReturn(text);
            ContentBlock block = mock(ContentBlock.class);
            when(block.text()).thenReturn(Optional.of(textBlock));
            return block;
        }).toList();
        Message message = mock(Message.class);
        when(message.stopReason()).thenReturn(Optional.of(stop));
        when(message.content()).thenReturn(blocks);
        AnthropicClient client = mock(AnthropicClient.class, RETURNS_DEEP_STUBS);
        when(client.messages().create(any(MessageCreateParams.class))).thenReturn(message);
        return client;
    }
}
