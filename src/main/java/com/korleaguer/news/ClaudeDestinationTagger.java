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
import com.korleaguer.domain.NewsDestination;
import com.korleaguer.support.MlbTeams;
import com.korleaguer.support.TeamNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Claude로 기사에서 행선지로 거론된 구단을 고른다(요약과 같은 모델, 기본 claude-haiku-5-5). 요약(3~4문장)과 제목만 보내서
 * 한 건에 요약보다 훨씬 싸다. 답은 한 줄에 '약어 +' 또는 '약어 -'(없으면 '없음')와 마지막 줄
 * '주인공: 예/아니오'(이 선수가 주인공인 기사인지, 이슈 #129·#131)다(JSON 없이 줄로 읽는다).
 */
public final class ClaudeDestinationTagger implements DestinationTagger {

    /** 지시문 판. 바꾸면 올린다(기사마다 적은 판과 다르면 다시 고른다) */
    static final String PROMPT_VERSION = "d3";
    /** destinations_model 칸 길이(V18 마이그레이션 varchar(50)) */
    static final int MAX_VERSION = 50;
    static final long MAX_TOKENS = 300;
    static final OutputConfig.Effort EFFORT = OutputConfig.Effort.LOW;

    static final String SYSTEM = """
            너는 야구 기사를 읽고, 기사가 다루는 한국 선수가 다음에 갈 MLB 구단(행선지) 후보로 어느 구단이 거론됐는지 고른다.
            제목과 기사를 모두 본다. 제목에 나온 구단도 빠뜨리지 않는다(예: '필라델피아 동반 이적 가능성'이면 필라델피아).

            구단 적는 법(한 줄에 한 구단, 약어는 아래 표의 것만):
            - 그 선수를 원한다, 관심이 있다, 잘 맞는다, 함께 데려갈 수 있다, 갈 가능성이 있다고 본 구단: '약어 +'
            - 그 선수가 필요 없다, 관심이 없다, 가지 않을 것이라고 본 구단: '약어 -'
            - 거론된 구단이 없으면 '없음'만 쓴다.

            적지 않는 것:
            - 다른 선수 이야기로만 나온 구단(그 선수와 함께 데려간다는 이야기면 적는다)
            - 사람 이름(예: '워싱턴 코치'의 워싱턴은 사람이다), 비교로만 나온 구단, 그 선수의 지금·옛 소속
            - KBO·일본 구단

            마지막 줄: 이 기사가 그 선수 이야기인지.
            - '주인공: 예' — 그 선수의 MLB 진출(포스팅·계약·관심 구단·평가·전망)을 다룬다. 다른 선수와 비교하거나
              코치·동료와의 인연을 다뤄도, 그 선수의 MLB 진출 이야기면 '예'다.
            - '주인공: 아니오' — 주제가 다른 이야기이고 그 선수 이름은 곁들여 나올 뿐이다
              (예: 그 선수가 떠난 KBO 자리를 누가 채울지, KBO 구단의 FA 영입 사정, 다른 선수의 계약 이야기).

            다른 말은 쓰지 않는다.

            약어 표:
            %s
            """;
    static final String USER_MESSAGE = "선수: %s\n제목: %s\n\n기사:\n%s";
    /** 요약은 1200자 안쪽이다. 앞부분만 있는 기사도 이만큼이면 된다 */
    static final int MAX_TEXT = 2000;

    /**
     * 한 줄의 앞머리: 글머리표(-·*·•·번호) 뒤 구단(약어 또는 한글 이름)과 방향 부호. 운영에서 거론이 있을 때 모델이
     * 'NYM +: 메츠 관심', '**NYM +**', 'NYM (+)'처럼 꾸며 답해 처음 규칙이 놓쳤다(이슈 #123). 부호 뒤는 무엇이 와도 된다
     */
    private static final Pattern LINE = Pattern.compile(
            "^\\s*(?:[-*•·]\\s+|\\d+[.)]\\s*)?([A-Za-z]{2,3}|[가-힣A-Z][가-힣A-Z ]*[가-힣])\\s*[:：]?\\s*[(（\\[]?\\s*([+\\-−])");
    /** 굵은 글씨·백틱 같은 꾸밈 표시(답을 읽기 전에 지운다) */
    private static final Pattern MARKUP = Pattern.compile("[*`_]");
    private static final Pattern NONE = Pattern.compile("^\\s*없음\\s*[.]?\\s*$");
    /** 첫 줄 '주인공: 예/아니오'(이슈 #129) */
    private static final Pattern ABOUT = Pattern.compile("^\\s*(?:[-*•·]\\s+|\\d+[.)]\\s*)?주인공\\s*[:：]?\\s*(예|아니오|아니요)");
    private static final Logger log = LoggerFactory.getLogger(ClaudeDestinationTagger.class);

    private final AnthropicClient client;
    private final String model;
    private final String system;
    private volatile boolean rejected;

    public ClaudeDestinationTagger(AnthropicClient client, String model) {
        if (!fitsRecord(model)) {
            throw new IllegalArgumentException("destination model name is too long to record: " + versionOf(model));
        }
        this.client = client;
        this.model = model;
        this.system = String.format(Locale.ROOT, SYSTEM, teamTable());
    }

    /** 이 모델로 고른 판이 destinations_model 칸(50자)에 들어가나 */
    public static boolean fitsRecord(String model) {
        return versionOf(model).length() <= MAX_VERSION;
    }

    static String versionOf(String model) {
        return model + "/" + PROMPT_VERSION;
    }

    /** 'NYM 뉴욕 메츠' 줄 30개 */
    static String teamTable() {
        return MlbTeams.abbreviations().stream()
                .map(a -> a + " " + TeamNames.korean(a, a))
                .collect(Collectors.joining("\n"));
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
    public Optional<Tagging> tag(String player, String title, String text) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                .thinking(ThinkingConfigDisabled.builder().build())
                .system(system)
                .outputConfig(OutputConfig.builder().effort(EFFORT).build())
                .addUserMessage(userMessage(player, title, text))
                .build();
        Message message;
        try {
            message = client.messages().create(params);
        } catch (UnauthorizedException | PermissionDeniedException e) {
            rejected = true;
            log.error("news: the Claude API rejected ANTHROPIC_API_KEY ({}); destination tagging is off until restart",
                    e.statusCode());
            throw new Summarizer.Failure(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        } catch (AnthropicException e) {
            throw new Summarizer.Failure(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
        // 거절이나 길이 제한에 잘린 답은 믿지 않는다(뒤쪽 구단 줄을 잃었을 수 있다). 기사 탓 실패로 센다
        if (message.stopReason().filter(r -> r.equals(StopReason.REFUSAL) || r.equals(StopReason.MAX_TOKENS)).isPresent()) {
            return Optional.empty();
        }
        String answer = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text())
                .collect(Collectors.joining("\n"));
        Optional<Tagging> picked = parse(answer);
        if (picked.isEmpty()) {
            // 읽지 못한 답의 모양을 보고 규칙을 고칠 수 있게 앞부분만 남긴다(기사 내용이 아니라 모델의 답이다)
            String shown = answer.replaceAll("\\R", " ⏎ ");
            log.warn("news: could not read the destination answer for '{}': {}", title,
                    shown.length() > 160 ? shown.substring(0, 160) + "…" : shown);
        }
        return picked;
    }

    static String userMessage(String player, String title, String text) {
        String body = text == null ? "" : text.strip();
        if (body.length() > MAX_TEXT) {
            body = body.substring(0, MAX_TEXT);
        }
        return String.format(Locale.ROOT, USER_MESSAGE, player, title, body.isEmpty() ? "(제목만 있다)" : body);
    }

    /**
     * 답을 읽는다. 표에 있는 약어 줄만 받고(SFG 같은 다른 약어는 대표 약어로, 부호 뒤 덧붙인 설명은 무시), 한 구단은 처음 나온 줄만 받는다.
     * '없음'이면 빈 목록. 읽을 줄이 하나도 없으면 empty(기사 탓 실패로 센다)
     */
    static Optional<Tagging> parse(String answer) {
        if (answer == null) {
            return Optional.empty();
        }
        Map<String, NewsDestination.Stance> picked = new LinkedHashMap<>();
        boolean none = false;
        Boolean aboutPlayer = null;
        for (String raw : answer.split("\\R")) {
            String line = MARKUP.matcher(raw).replaceAll("");
            Matcher about = ABOUT.matcher(line);
            if (about.find()) {
                aboutPlayer = about.group(1).equals("예");
                continue;
            }
            if (NONE.matcher(line).matches()) {
                none = true;
                continue;
            }
            Matcher m = LINE.matcher(line);
            if (!m.find()) {
                continue;
            }
            String team = team(m.group(1).trim());
            if (team == null) {
                continue;
            }
            NewsDestination.Stance stance = m.group(2).equals("+") ? NewsDestination.Stance.LINKED : NewsDestination.Stance.DOUBTED;
            picked.putIfAbsent(team, stance);
        }
        // 구단 줄도 '없음'도 '주인공' 줄도 없으면 읽지 못한 답이다. '주인공' 줄만 있으면 거론된 구단이 없는 것으로 본다
        if (picked.isEmpty() && !none && aboutPlayer == null) {
            return Optional.empty();
        }
        List<Destination> out = new ArrayList<>();
        picked.forEach((team, stance) -> out.add(new Destination(team, stance)));
        return Optional.of(new Tagging(List.copyOf(out), aboutPlayer));
    }

    /** 약어(SFG 같은 다른 약어도) 또는 표의 한글 이름 → 대표 약어. MLB 구단이 아니면 null */
    static String team(String name) {
        String abbreviation = MlbTeams.canonical(name.toUpperCase(Locale.ROOT));
        if (abbreviation != null) {
            return abbreviation;
        }
        return MlbTeams.abbreviations().stream()
                .filter(a -> TeamNames.korean(a, a).equals(name))
                .findFirst()
                .orElse(null);
    }
}
