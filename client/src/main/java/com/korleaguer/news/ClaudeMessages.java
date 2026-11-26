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

import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Claude에 한 번 묻고 답의 글을 받는다. 기사 요약(ClaudeSummarizer)·행선지 구단 고르기(ClaudeDestinationTagger)·
 * 하이라이트 제목 번역(ClaudeHighlightTitleTranslator)이 같은 방침을 쓴다.
 * <ul>
 *   <li>생각(thinking)은 끄고, 지시문·답 길이·노력 정도는 기능마다 정한다.</li>
 *   <li>API 키가 거절되면(401·403) 서버를 다시 띄울 때까지 그 기능을 끈다(enabled가 false).</li>
 *   <li>키 거절을 포함한 모든 API 오류(결제·모델 설정, 사용량 제한 429, 서버 문제 5xx·529, 연결 실패, 요청 거절 400)는
 *       Summarizer.Failure로 넘긴다. 부르는 쪽은 그 기사·제목의 시도로 세지 않고 다음 차례에 다시 한다.</li>
 * </ul>
 * 모델이 답을 거절했는지, 길이 제한에 잘렸는지는 Reply에 담아 돌려주고, 어떻게 다룰지는 기능마다 정한다.
 */
final class ClaudeMessages {

    private static final Logger log = LoggerFactory.getLogger(ClaudeMessages.class);

    private final AnthropicClient client;
    private final String model;
    /** 키가 거절돼 꺼질 때 남길 로그의 앞머리와 끝말 */
    private final String logArea;
    private final String offNotice;
    private volatile boolean rejected;

    /**
     * @param logArea   로그 앞머리(예: "news", "highlight titles")
     * @param offNotice 키가 거절돼 꺼질 때 로그 끝말(예: "summaries are off until restart")
     */
    ClaudeMessages(AnthropicClient client, String model, String logArea, String offNotice) {
        this.client = client;
        this.model = model;
        this.logArea = logArea;
        this.offNotice = offNotice;
    }

    /** 모델이 준 글(text 블록을 줄바꿈으로 이음)과, 답을 거절했는지·길이 제한에 잘렸는지 */
    record Reply(String text, boolean refused, boolean truncated) {
    }

    /** 키가 거절되지 않았으면 true */
    boolean enabled() {
        return !rejected;
    }

    /**
     * @throws Summarizer.Failure API가 답하지 못했다(키 거절이면 이 뒤로 enabled가 false)
     */
    Reply ask(String system, long maxTokens, OutputConfig.Effort effort, String userMessage) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .thinking(ThinkingConfigDisabled.builder().build())
                .system(system)
                .outputConfig(OutputConfig.builder().effort(effort).build())
                .addUserMessage(userMessage)
                .build();
        Message message;
        try {
            message = client.messages().create(params);
        } catch (UnauthorizedException | PermissionDeniedException e) {
            rejected = true;
            log.error("{}: the Claude API rejected ANTHROPIC_API_KEY ({}); {}", logArea, e.statusCode(), offNotice);
            throw new Summarizer.Failure(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        } catch (AnthropicException e) {
            throw new Summarizer.Failure(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
        Optional<StopReason> stop = message.stopReason();
        String text = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text())
                .collect(Collectors.joining("\n"));
        return new Reply(text, stop.filter(StopReason.REFUSAL::equals).isPresent(),
                stop.filter(StopReason.MAX_TOKENS::equals).isPresent());
    }
}
