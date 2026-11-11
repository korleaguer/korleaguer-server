package com.korleaguer.news;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;

import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Claude API(공식 Java SDK)로 영어 야구 기사를 한국어 3~4줄로 요약한다.
 * 모델은 korleaguer.news.summary-model(기본 claude-haiku-5-5: 요약처럼 단순한 일에 가장 싸고 빠르다).
 * 깊이 생각할 일이 아니라 effort는 low, 답 길이는 700토큰까지. 기사 한 건에 약 0.0005달러.
 */
public class ClaudeSummarizer implements Summarizer {

    static final int MAX_SUMMARY = 1200;

    static final String SYSTEM = """
            너는 한국 야구 팬 사이트에서 미국 야구 기사를 한국어로 요약한다.
            규칙:
            - 기사에 쓰인 사실만 쓴다. 추측·평가·전망을 덧붙이지 않는다(기사가 전한 전망은 '~라고 전했다'처럼 출처를 살린다).
            - 3~4문장. 한 줄에 한 문장, 줄바꿈으로 나눈다. 머리 기호·제목·인사말 없이 요약만 쓴다.
            - 존댓말 말고 기사체(~했다, ~이다)로 쓴다.
            - 한국 선수 이름은 한글로 쓴다(예: Si Hwan Roh → 노시환, Ha-Seong Kim → 김하성, Jung Hoo Lee → 이정후).
            - 구단 이름은 한국에서 흔히 쓰는 표기로 쓴다(예: New York Mets → 뉴욕 메츠, Hanwha Eagles → 한화 이글스).
            - 금액은 원문 단위를 살려 한국어로 쓴다(예: $23MM → 2,300만 달러).
            - 기자 이름·광고·댓글·다른 기사 소개는 넣지 않는다.
            """;

    private final AnthropicClient client;
    private final String model;

    public ClaudeSummarizer(AnthropicClient client, String model) {
        this.client = client;
        this.model = model;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Optional<String> summarize(String player, String title, String body) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(700L)
                .system(SYSTEM)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .addUserMessage("이 기사가 다루는 한국 선수: " + player + "\n제목: " + title + "\n\n본문:\n" + body)
                .build();
        Message message = client.messages().create(params);
        if (message.stopReason().map(r -> r.toString().equalsIgnoreCase("refusal")).orElse(false)) {
            return Optional.empty();
        }
        String text = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text())
                .collect(Collectors.joining("\n"))
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{2,}", "\n")
                .trim();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(text.length() > MAX_SUMMARY ? text.substring(0, MAX_SUMMARY - 1) + "…" : text);
    }

    @Override
    public String model() {
        return model;
    }
}
