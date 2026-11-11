package com.korleaguer.news;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.ThinkingConfigDisabled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Claude API(공식 Java SDK)로 영어 야구 기사를 한국어 3~4줄로 요약한다.
 * 모델은 korleaguer.news.summary-model(기본 claude-haiku-5-5: 요약처럼 단순한 일에 가장 싸고 빠르다).
 * 깊이 생각할 일이 아니라 effort는 low에 생각(thinking)은 끄고, 답 길이는 1000토큰까지. 기사 한 건에 약 0.0005달러.
 * 키가 거절되면(401·403) 서버를 다시 띄울 때까지 요약을 끈다(키를 고치면 어차피 다시 띄운다).
 */
public class ClaudeSummarizer implements Summarizer {

    static final int MAX_SUMMARY = 1200;
    static final int MAX_TITLE = 100;
    // '제목: …'. 모델이 마크다운으로 꾸며도(**제목:** …, # 제목: …) 알아본다
    private static final Pattern TITLE_LINE = Pattern.compile("^[#*\\s]*제목\\s*\\**\\s*[:：]\\s*\\**\\s*");
    private static final Pattern BULLET = Pattern.compile("^[-•·*]\\s+");
    private static final Pattern BOLD = Pattern.compile("\\*\\*");

    static final String SYSTEM = """
            너는 한국 야구 팬 사이트에서 미국 야구 기사를 한국어로 요약한다.
            규칙:
            - 기사에 쓰인 사실만 쓴다. 추측·평가·전망을 덧붙이지 않는다(기사가 전한 전망은 '~라고 전했다'처럼 출처를 살린다).
            - 요약은 2~3문장, 모두 합쳐 130자 안팎. 가장 중요한 사실부터 쓴다.
            - 한 줄에 한 문장, 줄바꿈으로 나눈다. 머리 기호·인사말은 쓰지 않는다.
            - 존댓말 말고 기사체(~했다, ~이다)로 쓴다.
            - 한국 선수 이름은 한글로 쓴다(예: Si Hwan Roh → 노시환, Ha-Seong Kim → 김하성, Jung Hoo Lee → 이정후).
            - 구단 이름은 한국에서 흔히 쓰는 표기로 쓴다(예: New York Mets → 뉴욕 메츠, Hanwha Eagles → 한화 이글스).
            - 금액은 원문 단위를 살려 한국어로 쓴다(예: $23MM → 2,300만 달러).
            - 기자 이름·광고·댓글·다른 기사 소개는 넣지 않는다.
            출력 형식(이 형식만 쓴다):
            제목: <기사 제목을 한국어 기사 제목처럼 옮긴 것, 35자 이내>
            <요약 2~3문장, 한 줄에 한 문장>
            """;

    private static final Logger log = LoggerFactory.getLogger(ClaudeSummarizer.class);

    private final AnthropicClient client;
    private final String model;
    private volatile boolean rejected;

    public ClaudeSummarizer(AnthropicClient client, String model) {
        this.client = client;
        this.model = model;
    }

    @Override
    public boolean enabled() {
        return !rejected;
    }

    @Override
    public Optional<Summary> summarize(String player, String title, String body) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(1000L)
                .thinking(ThinkingConfigDisabled.builder().build())
                .system(SYSTEM)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .addUserMessage("이 기사가 다루는 한국 선수: " + player + "\n제목: " + title + "\n\n본문:\n" + body)
                .build();
        Message message;
        try {
            message = client.messages().create(params);
        } catch (UnauthorizedException | PermissionDeniedException e) {
            rejected = true;
            log.error("news: the Claude API rejected ANTHROPIC_API_KEY ({}); summaries are off until restart",
                    e.statusCode());
            throw new Failure(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        } catch (AnthropicException e) {
            // 결제·모델 설정, 사용량 제한(429), 서버 문제(5xx·529), 연결 실패, 요청 거절(400)
            throw new Failure(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
        Optional<StopReason> stop = message.stopReason();
        if (stop.filter(StopReason.REFUSAL::equals).isPresent()) {
            return Optional.empty();
        }
        String text = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text())
                .collect(Collectors.joining("\n"));
        return tidy(text, stop.filter(StopReason.MAX_TOKENS::equals).isPresent());
    }

    /**
     * 답을 제목 줄과 요약으로 나누고 빈 줄·머리 기호를 정리한다. 답이 길이 제한에서 잘렸으면(truncated) 끝나지 않은
     * 마지막 줄을 버린다. 첫 줄이 '제목:'이 아니면 제목 없이 모두 요약으로 본다. 요약이 남지 않으면 empty.
     */
    static Optional<Summary> tidy(String text, boolean truncated) {
        List<String> lines = Arrays.stream(text.split("\\R"))
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .collect(Collectors.toList());
        if (truncated && !lines.isEmpty()) {
            lines.removeLast();
        }
        String titleKo = null;
        if (!lines.isEmpty()) {
            Matcher m = TITLE_LINE.matcher(lines.getFirst());
            if (m.find()) {
                String t = BOLD.matcher(lines.removeFirst().substring(m.end())).replaceAll("").strip();
                titleKo = t.isEmpty() ? null : cap(t, MAX_TITLE);
            }
        }
        String summary = lines.stream()
                .map(line -> BOLD.matcher(BULLET.matcher(line).replaceFirst("")).replaceAll("").strip())
                .filter(line -> !line.isEmpty())
                .collect(Collectors.joining("\n"));
        if (summary.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Summary(titleKo, cap(summary, MAX_SUMMARY)));
    }

    private static String cap(String text, int max) {
        return text.length() > max ? text.substring(0, max - 1) + "…" : text;
    }

    @Override
    public String model() {
        return model;
    }
}
