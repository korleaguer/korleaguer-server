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

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Claude API로 MLB 하이라이트 영어 제목을 한국어 한 줄로 옮긴다(이슈 #199). 뉴스 요약(ClaudeSummarizer)과 같은 방식·모델이다:
 * korleaguer.news.summary-model(기본 claude-haiku-5-5), effort low, 생각(thinking) 끔. 제목 한 건에 답이 짧아 아주 싸다
 * (답은 최대 MAX_TOKENS토큰). 키가 거절되면(401·403) 서버를 다시 띄울 때까지 끈다.
 */
public final class ClaudeHighlightTitleTranslator implements HighlightTitleTranslator {

    /**
     * 지시문과 요청 틀의 판. 바꾸면 올린다(제목마다 모델과 함께 적고, 다른 판으로 옮긴 옛 번역은 다시 옮긴다).
     * t1: 처음(이슈 #199)
     */
    static final String PROMPT_VERSION = "t1";
    /** highlight_title.prompt_version 칸 길이(V27 마이그레이션 varchar(50)) */
    static final int MAX_VERSION = 50;
    /** highlight_title.title_ko 칸 길이(V27 마이그레이션 varchar(100)) */
    static final int MAX_TITLE = 100;
    static final long MAX_TOKENS = 200;
    static final OutputConfig.Effort EFFORT = OutputConfig.Effort.LOW;

    static final String SYSTEM = """
            너는 한국 야구 팬 사이트에서 MLB 하이라이트 영상의 영어 제목을 한국어 한 줄로 옮긴다.
            요청에는 '화면의 선수'(영상이 뜨는 창의 선수)와 영어 제목이 온다. 화면이 이미 그 선수의 창이므로 제목에서 그 선수의 이름은 뺀다(예: 화면의 선수가 Jung Hoo Lee일 때 "Jung Hoo Lee's solo home run" → 솔로 홈런).
            규칙:
            - 제목에 적힌 사실만 옮긴다. 원문에 없는 말(평가·상황·결과·사람)을 덧붙이지 않는다. 뜻이 확실하지 않은 말은 짐작하지 말고 원문 그대로 직역한다.
            - 화면의 선수 이름만 뺀다. 제목에 다른 선수가 나오면 한국에서 흔히 쓰는 한글 표기로 쓰고(예: Nolan Arenado → 놀란 아레나도), 이름이 성만 적혀 있으면 성만 쓴다. 화면의 선수가 제목에 나오지 않으면 이름을 빼는 일 없이 그대로 옮긴다.
            - 괄호 속 숫자는 홈런이면 시즌 누적 번호다: '(7)'은 '(시즌 7호)'. 그 밖의 괄호 숫자는 그대로 둔다.
            - 구단 이름은 한국에서 흔히 쓰는 표기로 쓴다(예: Giants → 자이언츠, Padres → 파드리스, Dodgers → 다저스, Braves → 브레이브스, Brewers → 브루어스, Rangers → 레인저스).
            - 야구 용어는 한국에서 흔히 쓰는 말로 쓴다. 예: RBI single → 적시타, two-run single → 2타점 적시타, RBI double → 적시 2루타, two-run double → 2타점 2루타, two-run home run → 투런 홈런, solo home run → 솔로 홈런, sacrifice fly → 희생플라이, groundout → 땅볼 아웃, walk-off → 끝내기, pinch-hit → 대타, sliding catch·grab·snag → 슬라이딩 캐치, diving stop → 다이빙 수비, backhanded play → 백핸드 수비, steals → 도루, ABS challenge → ABS 챌린지, overturned → 번복, confirmed → 유지, strikes out the side → 세 타자 연속 삼진, Field View → 필드 뷰, Korean call → 한국어 중계, RBI groundout → 타점 땅볼 아웃, stands(판정이 그대로 인정됨) → 판정 유지, comes through in a pinch → 결정적 순간에 해결.
            - 속어·과장 표현은 점잖은 말로 옮긴다(insane → 대단한, nasty → 대단한).
            - 짧은 명사구로 쓴다. 문장으로 끝내지 않고 마침표를 붙이지 않는다. 30자 안쪽을 목표로 한다.
            - 출력은 옮긴 제목 한 줄뿐이다. 생각 과정, 이름을 뺐다는 말, 설명, 따옴표, 머리말('제목:'), 여러 줄을 쓰지 않는다.
            """;

    /** 요청 사용자 메시지 틀: 화면의 선수(한글, 영어), 영어 제목 */
    static final String USER_MESSAGE = "화면의 선수: %s (%s)\n제목: %s";

    private static final Pattern HANGUL = Pattern.compile("[가-힣]");
    private static final Pattern LABEL = Pattern.compile("^[#*\\s]*(?:제목|한국어 제목)\\s*\\**\\s*[:：]\\s*\\**\\s*");
    /** 제목 대신 옮긴 방법을 설명한 답('선수 이름을 빼고 옮기면: …') */
    private static final Pattern META = Pattern.compile("선수 이름|이름을? 빼|제외하고|옮기면|옮긴|번역|주인공|[Ww]ait|제목");
    private static final Pattern WRAPPING = Pattern.compile("^[\"“'‘`*]+|[\"”'’`*]+$");

    private static final Logger log = LoggerFactory.getLogger(ClaudeHighlightTitleTranslator.class);

    private final AnthropicClient client;
    private final String model;
    private volatile boolean rejected;

    public ClaudeHighlightTitleTranslator(AnthropicClient client, String model) {
        // 판 이름이 DB 칸에 들어가지 않으면 저장할 때마다 실패하고 같은 제목을 계속 다시 옮긴다. 설정(NewsConfig)이 먼저 걸러 끈다
        if (!fitsRecord(model)) {
            throw new IllegalArgumentException("model name is too long to record: " + versionOf(model)
                    + " (up to " + MAX_VERSION + " characters)");
        }
        this.client = client;
        this.model = model;
    }

    /** 이 모델로 옮긴 제목의 판(모델/지시문 판)이 prompt_version 칸(50자)에 들어가나 */
    public static boolean fitsRecord(String model) {
        return versionOf(model).length() <= MAX_VERSION;
    }

    static String versionOf(String model) {
        return model + "/" + PROMPT_VERSION;
    }

    @Override
    public boolean enabled() {
        return !rejected;
    }

    @Override
    public String version() {
        return versionOf(model);
    }

    @Override
    public Optional<String> translate(String titleEn, String playerKo, String playerEn) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                .thinking(ThinkingConfigDisabled.builder().build())
                .system(SYSTEM)
                .outputConfig(OutputConfig.builder().effort(EFFORT).build())
                .addUserMessage(String.format(Locale.ROOT, USER_MESSAGE, playerKo, playerEn, titleEn))
                .build();
        Message message;
        try {
            message = client.messages().create(params);
        } catch (UnauthorizedException | PermissionDeniedException e) {
            rejected = true;
            log.error("highlight titles: the Claude API rejected ANTHROPIC_API_KEY ({}); translations are off until restart",
                    e.statusCode());
            throw new Summarizer.Failure(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        } catch (AnthropicException e) {
            throw new Summarizer.Failure(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
        Optional<StopReason> stop = message.stopReason();
        if (stop.filter(s -> StopReason.REFUSAL.equals(s) || StopReason.MAX_TOKENS.equals(s)).isPresent()) {
            return Optional.empty();
        }
        String text = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text())
                .collect(Collectors.joining("\n"));
        return tidy(text);
    }

    /**
     * 답에서 제목 한 줄을 뽑는다. 머리말('제목:')·따옴표·마크다운을 걷어 내고, 한글이 없거나(옮기지 못함) 한 줄이 아니거나 옮긴 방법을 설명하거나
     * 칸(MAX_TITLE)보다 길면 empty다(못 쓸 답을 화면에 내지 않는다).
     */
    static Optional<String> tidy(String text) {
        String[] lines = text.strip().split("\\R+");
        if (lines.length != 1) {
            return Optional.empty();
        }
        String line = WRAPPING.matcher(LABEL.matcher(lines[0].strip()).replaceFirst("")).replaceAll("").strip();
        if (line.isEmpty() || line.length() > MAX_TITLE || !HANGUL.matcher(line).find()
                || META.matcher(line).find()) {
            return Optional.empty();
        }
        return Optional.of(line);
    }
}
