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

import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Claude API(공식 Java SDK)로 야구 기사(한국어·영어)를 한국어로 요약한다. 길이는 본문 분량을 따른다: 본문 길이로 최대 문장 수
 * (3~7, maxSentences)를 정해 기사 날짜와 함께 요청에 넣고, 그보다 크게 넘긴 문장은 버린다(tidy). 모델에게 분량 판단만 맡기면
 * 기사 길이와 상관없이 5~9문장을 써서(운영 기사 30건 시험, 이슈 #103) 짧은 기사까지 부풀렸다.
 * 모델은 korleaguer.news.summary-model(기본 claude-haiku-5-5: 요약처럼 단순한 일에 가장 싸고 빠르다).
 * 깊이 생각할 일이 아니라 effort는 low에 생각(thinking)은 끄고, 답 길이는 1500토큰까지. 기사 한 건에 약 0.001달러 안쪽.
 * 키가 거절되면(401·403) 서버를 다시 띄울 때까지 요약을 끈다(키를 고치면 어차피 다시 띄운다).
 */
public final class ClaudeSummarizer implements Summarizer {

    /**
     * 요약 지시문과 요청(SYSTEM·요청 틀·본문 손질)의 판. 바꾸면 올린다. 요약에 모델 이름과 함께 적고(summary_model),
     * 옛 판으로 만든 요약은 차례로 다시 요약한다(NewsEnricher). v1: 2~3문장 130자 안팎(11-11),
     * v2: 본문 분량에 맞춘 최대 3~7문장, 기사 날짜, 숫자·날짜·계약 금액·선수 규칙(11-12, 이슈 #103 "쓸 내용이 많은데
     * 지나치게 줄이지 말라"), v3: 영어 본문의 'last February'에 연도를 붙여 보냄(11-12, 이슈 #105 '지난해 2월' 오역).
     * 바꾸면 ClaudeSummarizerTest의 지문도 새 값으로 고친다(판을 올리는 것을 잊지 않게)
     */
    static final String PROMPT_VERSION = "v3";
    /** summary_model 칸 길이(V12 마이그레이션 varchar(50)) */
    static final int MAX_VERSION = 50;
    static final int MAX_SUMMARY = 1200;
    static final int MAX_TITLE = 100;
    // '제목: …'. 모델이 마크다운으로 꾸며도(**제목:** …, # 제목: …) 알아본다
    private static final Pattern TITLE_LINE = Pattern.compile("^[#*\\s]*제목\\s*\\**\\s*[:：]\\s*\\**\\s*");
    private static final Pattern BULLET = Pattern.compile("^[-•·*]\\s+");
    private static final Pattern BOLD = Pattern.compile("\\*\\*");
    /**
     * 요약 대신 지시를 옮겨 쓴 답('요약 분량 기준으로 4문장 이내로 쓴다'). 시험 319번 중 1번 나왔다.
     * 이런 답은 버리고 다음 차례에 다시 요약한다(옛 요약이 있으면 그대로 둔다)
     */
    private static final Pattern ECHOED_INSTRUCTION = Pattern.compile("요약 분량|\\d+\\s*문장\\s*이내");

    static final String SYSTEM = """
            너는 한국 야구 팬 사이트에서 야구 기사(한국어나 영어)를 한국어로 요약한다.
            규칙:
            - 기사에 쓰인 사실만 쓴다. 너의 추측·평가·전망은 덧붙이지 않는다. 기사 속 평가·전망은 누가 했는지 밝혀 쓴다('~라고 평가했다', '~라고 전했다').
            - 가장 중요한 사실부터 쓴다.
            - 이 기사가 다루는 한국 선수 이야기가 기사에 있으면 그 내용을 꼭 한 문장 넣는다.
            - 문장 수는 '요약 분량'에 적힌 수를 넘기지 않는다. 그 안에서 기사가 전하는 서로 다른 사실만큼만 쓰고, 쓸 사실이 떨어지면 거기서 멈춘다.
            - 같은 사실을 다른 말로 다시 쓰거나 기사에 없는 말을 보태지 않는다.
            - 숫자는 원문의 값을 그대로 옮긴다. 두 숫자를 섞거나 계산해 새 숫자를 만들지 않는다.
            - 날짜·연도는 기사에 쓰인 대로 쓴다. '올해'·'올 시즌'처럼 쓰였으면 연도로 바꾸지 않는다. 영어 기사의 상대 날짜('last February', 'this season')는 '기사 날짜'를 기준으로 옮긴다(2026년 11월 기사의 'last February'는 '지난 2월').
            - 계약 금액은 기사가 쓴 대로 총액·연봉·연평균을 구분한다. 기사에 '연봉'·'연평균'·'연간'·'연 ○○'·'per year'·'annual'·'AAV' 같은 말이 없으면 총액이다('4년 100억 원'은 총액이다).
            - 한 문장은 80자 안쪽으로 짧게 끊는다.
            - 한 줄에 한 문장, 줄바꿈으로 나눈다. 머리 기호·인사말은 쓰지 않는다.
            - 존댓말 말고 기사체(~했다, ~이다)로 쓴다.
            - 한국 선수 이름은 한글로 쓴다(예: Si Hwan Roh → 노시환, Ha-Seong Kim → 김하성, Jung Hoo Lee → 이정후).
            - 구단 이름은 한국에서 흔히 쓰는 표기로 쓴다(예: New York Mets → 뉴욕 메츠, Hanwha Eagles → 한화 이글스).
            - 금액은 원문 단위를 살려 한국어로 쓴다(예: $23MM → 2,300만 달러, 12.5 billion won → 125억 원).
            - 기자 이름·광고·댓글·다른 기사 소개는 넣지 않는다.
            출력 형식(이 형식만 쓴다):
            제목: <기사 제목을 한국어 기사 제목처럼 옮긴 것(한국어 기사면 원래 제목을 그대로), 35자 이내>
            <요약, 한 줄에 한 문장>
            """;

    /** 본문 이 글자 수마다 요약 1문장(한국어 기준) */
    static final int BODY_CHARS_PER_SENTENCE = 300;
    static final int MIN_SENTENCES = 3;
    static final int MAX_SENTENCES = 7;
    /**
     * 모델이 최대 문장 수를 넘기면 이만큼 넘는 것까지는 둔다. 딱 맞춰 자르면 모델이 뒤쪽에 쓴 계약 금액이 자주 빠졌다
     * (영어 기사 1번 10회 중 남은 횟수: 여유 0이면 5번, 1이면 8번). 13문장 같은 큰 초과는 자른다
     */
    static final int LENGTH_SLACK = 1;
    /** 영어 본문은 같은 내용도 한국어보다 길어 이만큼 나눠 센다 */
    static final double ENGLISH_LENGTH_FACTOR = 2.5;
    private static final Pattern HANGUL = Pattern.compile("[가-힣]");
    private static final Pattern LETTER = Pattern.compile("[가-힣A-Za-z]");
    /**
     * 영어 'last February'·'this past March 14th'·'last March 14-16' 같은 지난 달 표현. 달 이름은 대문자로 시작하는 것만
     * 본다. 연도가 이미 붙었으면(last February 2025, last February 20, 2025) 건드리지 않는다. 'at last May …'(드디어)와
     * 'his last April start'·'the last February day'(그 달의 마지막)는 지난 달 표현이 아니다
     */
    private static final Pattern LAST_MONTH = Pattern.compile(
            "(?<!\\b(?:[Aa]t|the|his|her|their|its|my|our|your) )\\b(?:[Ll]ast|[Tt]his past) "
                    + "(January|February|March|April|May|June|July|August|September|October|November|December)"
                    + "(?!(?: \\d{1,2}(?:[-–]\\d{1,2})?(?:st|nd|rd|th)?,?)?\\s\\d{4}\\b)"
                    + "(?: (\\d{1,2}(?:[-–]\\d{1,2})?)(?:st|nd|rd|th)?\\b)?\\b");
    /** 요청 사용자 메시지 틀: 선수, 제목, 기사 날짜(한국 날짜, 2026-11-02), 최대 문장 수(maxSentences), 본문 */
    static final String USER_MESSAGE = "이 기사가 다루는 한국 선수: %s\n제목: %s\n기사 날짜: %s\n요약 분량: %d문장 이내\n\n본문:\n%s";
    static final long MAX_TOKENS = 1500;
    static final OutputConfig.Effort EFFORT = OutputConfig.Effort.LOW;

    private static final Logger log = LoggerFactory.getLogger(ClaudeSummarizer.class);

    private final AnthropicClient client;
    private final String model;
    private volatile boolean rejected;

    public ClaudeSummarizer(AnthropicClient client, String model) {
        // 판 이름이 DB 칸(summary_model)에 들어가지 않으면 저장할 때마다 실패하고 같은 기사를 계속 다시 요약한다.
        // 설정(NewsConfig)이 먼저 걸러 요약만 끄므로 여기까지 오면 코드 실수다
        if (!fitsRecord(model)) {
            throw new IllegalArgumentException("summary model name is too long to record: " + versionOf(model)
                    + " (up to " + MAX_VERSION + " characters)");
        }
        this.client = client;
        this.model = model;
    }

    /** 이 모델로 만든 요약의 판(모델/지시문 판)이 summary_model 칸(50자)에 들어가나 */
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
    public Optional<Summary> summarize(String player, String title, LocalDate published, String body) {
        int most = maxSentences(body);
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                .thinking(ThinkingConfigDisabled.builder().build())
                .system(SYSTEM)
                .outputConfig(OutputConfig.builder().effort(EFFORT).build())
                .addUserMessage(userMessage(player, title, published, most, body))
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
        return tidy(text, stop.filter(StopReason.MAX_TOKENS::equals).isPresent(), most, player);
    }

    /**
     * 요청 사용자 메시지. 영어 본문의 'last February'는 연도를 붙여(February 2026) 보낸다(absoluteMonths).
     * %d가 서버 로캘에 따라 다른 숫자 모양이 되지 않게 Locale.ROOT. 기사 날짜는 2026-11-02 꼴
     */
    static String userMessage(String player, String title, LocalDate published, int most, String body) {
        return String.format(Locale.ROOT, USER_MESSAGE, player, title, published, most, absoluteMonths(body, published));
    }

    /**
     * 영어 'last February'를 기사 날짜 기준 가장 가까운 지난 그 달로 바꾼다(2026-11-02 기사면 February 2026,
     * last November는 November 2025, last February 14th는 February 14, 2026). 기사 날짜를 요청에 주고 지시문에
     * 예까지 적어도 모델이 11월 기사의 'signed last February'를 '지난해 2월'로 옮겼다(같은 요청 30번 중 7번, 이슈 #105).
     * 달이 뒤에 오는 'February of last year' 같은 말은 바꾸지 않는다
     */
    static String absoluteMonths(String body, LocalDate published) {
        if (published == null) {
            return body;
        }
        Matcher m = LAST_MONTH.matcher(body);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Month month = Month.valueOf(m.group(1).toUpperCase(Locale.ROOT));
            int year = month.getValue() < published.getMonthValue() ? published.getYear() : published.getYear() - 1;
            String date = m.group(2) == null ? m.group(1) + " " + year : m.group(1) + " " + m.group(2) + ", " + year;
            m.appendReplacement(out, Matcher.quoteReplacement(date));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * 답을 제목 줄과 요약으로 나누고 빈 줄·머리 기호를 정리한다. 답이 길이 제한에서 잘렸으면(truncated) 끝나지 않은
     * 마지막 줄을 버린다. 첫 줄이 '제목:'이 아니면 제목 없이 모두 요약으로 본다. 요약이 남지 않으면 empty.
     * 요약 대신 지시('요약 분량 …문장 이내')를 옮겨 쓴 답도 empty다(화면에 내지 않고 다음 차례에 다시 요약한다).
     * 요약은 문장마다 한 줄로 고르고, 최대 문장 수(most)에 LENGTH_SLACK을 더한 수를 넘는 문장은 버린다. 모델이 상한을
     * 크게 넘기는 일이 있어서다(7문장 상한에 13문장). 버리다 선수(player) 이야기가 모두 빠지면, 버린 쪽에서 선수가
     * 나오는 첫 문장을 마지막 자리에 둔다(다른 선수가 주인공인 기사도 이 선수의 기사 페이지에 뜬다).
     */
    static Optional<Summary> tidy(String text, boolean truncated, int most, String player) {
        List<String> lines = Arrays.stream(text.split("\\R"))
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .collect(Collectors.toList());
        if (lines.stream().anyMatch(line -> ECHOED_INSTRUCTION.matcher(line).find())) {
            log.warn("news: the summary echoed the length instruction, will retry: {}", String.join(" / ", lines));
            return Optional.empty();
        }
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
        List<String> sentences = sentences(lines.stream()
                .map(line -> BOLD.matcher(BULLET.matcher(line).replaceFirst("")).replaceAll("").strip())
                .filter(line -> !line.isEmpty())
                .collect(Collectors.joining("\n")));
        if (sentences.isEmpty()) {
            return Optional.empty();
        }
        List<String> kept = new ArrayList<>(sentences.subList(0, Math.min(most + LENGTH_SLACK, sentences.size())));
        if (player != null && !player.isBlank() && kept.stream().noneMatch(s -> s.contains(player))) {
            sentences.stream().skip(kept.size()).filter(s -> s.contains(player)).findFirst()
                    .ifPresent(s -> kept.set(kept.size() - 1, s));
        }
        return Optional.of(new Summary(titleKo, cap(String.join("\n", kept), MAX_SUMMARY)));
    }

    /** 문장 끝: 한글 + 마침표(+ 닫는 따옴표) 다음 빈칸. 빈칸은 웹(JS \s)처럼 NBSP·전각 공백까지 */
    private static final Pattern SENTENCE_END = Pattern.compile("[가-힣]\\.[”\"]?(\\s+)", Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * 요약을 문장으로 나눈다. 웹 기사 페이지(splitSentences)와 같은 규칙이다: 줄바꿈과 한국어 문장 끝에서 나누고,
     * 따옴표 짝이 맞을 때만 끊어 인용구 가운데서는 나누지 않는다. 숫자(0.283)·영문 약어(Jr.)는 나누지 않는다.
     */
    static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            int start = 0;
            Matcher m = SENTENCE_END.matcher(line);
            while (m.find()) {
                int end = m.end() - m.group(1).length();
                if (!balancedQuotes(line.substring(start, end))) {
                    continue;
                }
                out.add(line.substring(start, end).strip());
                start = m.end();
            }
            out.add(line.substring(start).strip());
        }
        out.removeIf(String::isEmpty);
        return out;
    }

    /** 여는·닫는 따옴표 짝이 맞는지(“” 수가 같고 " 수가 짝수) */
    private static boolean balancedQuotes(String s) {
        long open = s.chars().filter(c -> c == '“').count();
        long close = s.chars().filter(c -> c == '”').count();
        long straight = s.chars().filter(c -> c == '"').count();
        return open == close && straight % 2 == 0;
    }

    private static String cap(String text, int max) {
        return text.length() > max ? text.substring(0, max - 1) + "…" : text;
    }

    /**
     * 요약 최대 문장 수: 본문 300자마다 1문장, 3~7문장. 영어 본문(글자 가운데 한글이 30% 미만)은 같은 내용도 한국어보다 길어
     * 2.5로 나눠 센다. 운영 기사 30건(본문 467~3,842자)으로 시험해 짧은 기사는 지금처럼 3문장 안팎, 긴 기사는 6~7문장이 됐다
     * (본문 길이와 요약 글자 수의 순위 상관 0.04 → 0.75).
     */
    static int maxSentences(String body) {
        long hangul = HANGUL.matcher(body).results().count();
        long letters = Math.max(1, LETTER.matcher(body).results().count());
        double size = hangul * 10 >= letters * 3 ? body.length() : body.length() / ENGLISH_LENGTH_FACTOR;
        int sentences = (int) Math.ceil(size / BODY_CHARS_PER_SENTENCE);
        return Math.clamp(sentences, MIN_SENTENCES, MAX_SENTENCES);
    }

    @Override
    public String model() {
        return model;
    }

    /** 모델과 지시문 판(예: claude-haiku-5-5/v3). 요약에 적어 두고, 이 값이 다른 옛 요약은 다시 요약한다 */
    @Override
    public String version() {
        return versionOf(model);
    }
}
