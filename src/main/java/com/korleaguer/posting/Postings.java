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
            case "SIGNED" -> signed(r.mlbTeam(), r.agreedOn(), r.years(), r.guaranteedUsd(), who + " result");
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
                : signed(p.mlbTeam(), p.agreedOn(), p.years(), p.guaranteedUsd(), who);
        if (result.agreedOn() != null && result.agreedOn().isAfter(deadline)) {
            throw invalid(who, "agreed-on must not be after deadline");
        }
        return new PastPosting(p.nameKo().trim(), p.season(), deadline, result, p.source());
    }

    private static PostingResult signed(String mlbTeam, String agreedOn, Integer years, Long guaranteedUsd,
                                        String who) {
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
        return new PostingResult(PostingResult.Outcome.SIGNED, teamId, agreed, years, guaranteedUsd);
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
