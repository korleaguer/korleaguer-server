package com.korleaguer.posting;

import com.korleaguer.support.MlbTeams;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * posting.yml을 읽어 검사한 포스팅 자료. 사람이 적는 값이라 빠진 칸·틀린 형식·말이 안 되는 날짜가 있으면
 * 앱이 뜰 때 어느 선수의 어느 칸인지 밝히고 바로 실패한다(틀린 숫자가 화면에 나가지 않게).
 */
@Component
public class Postings {

    /** 원/달러 환율(1달러당 원)로 받는 범위 */
    static final double MIN_KRW_PER_USD = 500;
    static final double MAX_KRW_PER_USD = 3000;

    private final List<PostingCase> current;
    private final List<PastPosting> history;

    public Postings(PostingProperties properties) {
        this.current = properties.current().stream().map(Postings::current).toList();
        this.history = properties.history().stream().map(Postings::past).toList();
    }

    /** 이번 겨울 포스팅된 선수(파일에 적은 순서) */
    public List<PostingCase> current() {
        return current;
    }

    /** 지금 제도의 지난 포스팅(파일에 적은 순서) */
    public List<PastPosting> history() {
        return history;
    }

    /** 지금 관련 기사를 모을 선수(협상 시작 며칠 전 ~ 마감 며칠 뒤) */
    public List<PostingCase> newsWatch(Instant now) {
        return current.stream().filter(p -> p.watchNews(now)).toList();
    }

    private static PostingCase current(PostingProperties.Current c) {
        String who = "posting.current " + c.nameKo();
        require(c.nameKo(), who, "name-ko");
        require(c.kboTeam(), who, "kbo-team");
        require(c.positionKo(), who, "position-ko");
        Instant opens = instant(require(c.windowOpens(), who, "window-opens"), who, "window-opens");
        Instant deadline = instant(require(c.deadline(), who, "deadline"), who, "deadline");
        if (!deadline.isAfter(opens)) {
            throw invalid(who, "deadline must be after window-opens");
        }
        List<String> names = new ArrayList<>();
        names.add(c.nameKo().trim());
        c.searchNames().stream()
                .filter(name -> name != null && !name.isBlank())
                .map(String::trim)
                .filter(name -> !names.contains(name))
                .forEach(names::add);
        PostingResult result = c.result() == null ? null : result(c.result(), who);
        String slug = blankToNull(c.slug());
        String photoUrl = blankToNull(c.photoUrl());
        if (slug != null && !slug.matches("[a-z0-9-]{1,40}")) {
            throw invalid(who, "slug must be lowercase letters, digits and '-': " + slug);
        }
        if (photoUrl != null && slug == null) {
            throw invalid(who, "slug is required when photo-url is set");
        }
        if (photoUrl != null && !photoUrl.startsWith("https://")) {
            throw invalid(who, "photo-url must start with https://");
        }
        return new PostingCase(c.nameKo().trim(), slug, c.kboTeam().trim(), c.positionKo().trim(), c.birthYear(), names,
                opens, deadline, c.source(), photoUrl, result);
    }

    private static PostingResult result(PostingProperties.Result r, String who) {
        String outcome = require(r.outcome(), who, "result.outcome").trim().toUpperCase(Locale.ROOT);
        return switch (outcome) {
            case "NO_DEAL" -> PostingResult.noDeal();
            case "SIGNED" -> signed(r.mlbTeam(), r.agreedOn(), r.years(), r.guaranteedUsd(), r.krwPerUsd(), who + " result");
            default -> throw invalid(who, "result.outcome must be SIGNED or NO_DEAL: " + r.outcome());
        };
    }

    private static PastPosting past(PostingProperties.Past p) {
        String who = "posting.history " + p.nameKo();
        require(p.nameKo(), who, "name-ko");
        if (p.season() == null) {
            throw invalid(who, "season is required");
        }
        LocalDate deadline = date(require(p.deadline(), who, "deadline"), who, "deadline");
        PostingResult result = p.agreedOn() == null || p.agreedOn().isBlank()
                ? PostingResult.noDeal()
                : signed(p.mlbTeam(), p.agreedOn(), p.years(), p.guaranteedUsd(), p.krwPerUsd(), who);
        if (result.agreedOn() != null && result.agreedOn().isAfter(deadline)) {
            throw invalid(who, "agreed-on must not be after deadline");
        }
        if (p.kboId() != null && p.kboId() < 1) {
            throw invalid(who, "kbo-id must be a positive KBO player number: " + p.kboId());
        }
        String photoUrl = blankToNull(p.photoUrl());
        if (photoUrl != null) {
            if (!photoUrl.startsWith("https://")) {
                throw invalid(who, "photo-url must start with https://");
            }
            if (p.mlbId() == null || p.mlbId() < 1) {
                throw invalid(who, "mlb-id is required when photo-url is set");
            }
            if (p.photoSeason() == null || p.photoSeason() < 1990 || p.photoSeason() > 2100) {
                throw invalid(who, "photo-season (year of the photo) is required when photo-url is set");
            }
        }
        return new PastPosting(p.nameKo().trim(), p.season(), deadline, result, p.source(), p.kboId(),
                photoUrl == null ? null : p.mlbId(), photoUrl == null ? null : p.photoSeason(), photoUrl);
    }

    private static PostingResult signed(String mlbTeam, String agreedOn, Integer years, Long guaranteedUsd,
                                        Double krwPerUsd, String who) {
        Long teamId = MlbTeams.id(require(mlbTeam, who, "mlb-team").trim().toUpperCase(Locale.ROOT));
        if (teamId == null) {
            throw invalid(who, "unknown mlb-team: " + mlbTeam);
        }
        LocalDate agreed = date(require(agreedOn, who, "agreed-on"), who, "agreed-on");
        if (years == null || years < 1) {
            throw invalid(who, "years must be 1 or more");
        }
        if (guaranteedUsd == null || guaranteedUsd < 0) {
            throw invalid(who, "guaranteed-usd is required");
        }
        // 원/달러 환율은 1990년대 이후 700~2,000원 사이였다. 달러당이 아닌 값(예: 0.00068)이나 자릿수 실수를 막는다
        if (krwPerUsd != null && (krwPerUsd.isNaN() || krwPerUsd < MIN_KRW_PER_USD || krwPerUsd > MAX_KRW_PER_USD)) {
            throw invalid(who, "krw-per-usd must be won per dollar between " + (int) MIN_KRW_PER_USD + " and "
                    + (int) MAX_KRW_PER_USD + ": " + krwPerUsd);
        }
        return new PostingResult(PostingResult.Outcome.SIGNED, teamId, agreed, years, guaranteedUsd, krwPerUsd);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String require(String value, String who, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(who, field + " is required");
        }
        return value;
    }

    private static Instant instant(String text, String who, String field) {
        try {
            return OffsetDateTime.parse(text.trim()).toInstant();
        } catch (DateTimeParseException e) {
            throw invalid(who, field + " must be an ISO date-time with offset (2026-11-02T08:00:00-05:00): " + text);
        }
    }

    private static LocalDate date(String text, String who, String field) {
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw invalid(who, field + " must be yyyy-MM-dd: " + text);
        }
    }

    private static IllegalStateException invalid(String who, String message) {
        return new IllegalStateException("roster/posting.yml " + who + ": " + message);
    }
}
