package com.korleaguer.news;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * robots.txt 규칙(RFC 9309).
 * <ul>
 *   <li>그룹: 연달아 나오는 user-agent 줄들 + 그 뒤 allow·disallow 줄들. 이름이 맞는 그룹이 여럿이면 합친다.</li>
 *   <li>이름(제품 토큰)은 대소문자를 가리지 않고 같아야 맞는다. 맞는 그룹이 없으면 '*' 그룹을 쓰고, 그것도 없으면 다 허용이다.</li>
 *   <li>경로는 앞에서부터 맞춘다. '*'는 아무 글자들, 끝의 '$'는 경로 끝이다. 맞는 규칙 중 가장 긴 것이 이기고,
 *       길이가 같으면 allow가 이긴다. /robots.txt 자체는 늘 허용이다.</li>
 *   <li>crawl-delay는 표준은 아니지만 적혀 있으면 지킨다(요청 간격을 그보다 짧게 하지 않는다).</li>
 * </ul>
 */
public final class RobotsTxt {

    /** 받을 수 없을 때(4xx: 파일 없음) 쓰는 '다 허용' */
    public static final RobotsTxt ALLOW_ALL = new RobotsTxt(List.of());
    /** 서버 오류·연결 실패(5xx 등)일 때 쓰는 '다 막힘'(RFC 9309 2.3.1.4) */
    public static final RobotsTxt DISALLOW_ALL = new RobotsTxt(List.of(
            new Group(List.of("*"), List.of(new Rule(false, "/")), null)));

    // 파일이 너무 크면 앞부분만 읽는다(RFC 9309: 적어도 500KiB는 읽어야 한다)
    private static final int MAX_CHARS = 512 * 1024;

    private final List<Group> groups;

    private RobotsTxt(List<Group> groups) {
        this.groups = groups;
    }

    public static RobotsTxt parse(String text) {
        if (text == null || text.isBlank()) {
            return ALLOW_ALL;
        }
        String body = text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
        List<Group> groups = new ArrayList<>();
        List<String> agents = new ArrayList<>();
        List<Rule> rules = new ArrayList<>();
        Duration crawlDelay = null;
        boolean inRules = false;
        for (String raw : body.split("\r\n|\r|\n")) {
            int hash = raw.indexOf('#');
            String line = (hash >= 0 ? raw.substring(0, hash) : raw).trim();
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            switch (key) {
                case "user-agent" -> {
                    if (inRules) {
                        groups.add(new Group(List.copyOf(agents), List.copyOf(rules), crawlDelay));
                        agents.clear();
                        rules.clear();
                        crawlDelay = null;
                        inRules = false;
                    }
                    agents.add(token(value));
                }
                case "allow", "disallow" -> {
                    if (agents.isEmpty()) {
                        continue; // 그룹 밖의 규칙은 무시한다
                    }
                    inRules = true;
                    if (!value.isEmpty()) {
                        rules.add(new Rule(key.equals("allow"), value));
                    }
                }
                case "crawl-delay" -> {
                    if (!agents.isEmpty()) {
                        inRules = true;
                        crawlDelay = seconds(value);
                    }
                }
                default -> {
                    // sitemap 등은 그룹과 상관없다
                }
            }
        }
        if (!agents.isEmpty()) {
            groups.add(new Group(List.copyOf(agents), List.copyOf(rules), crawlDelay));
        }
        return new RobotsTxt(List.copyOf(groups));
    }

    /**
     * 이 이름의 수집기가 이 경로를 받아도 되는지.
     *
     * @param agent 제품 토큰(예: korleaguer, ClaudeBot)
     * @param path  경로와 쿼리(예: /rss/sports.xml?page=1). 비었으면 "/"
     */
    public boolean allows(String agent, String path) {
        String target = path == null || path.isEmpty() ? "/" : path;
        if (target.equals("/robots.txt")) {
            return true;
        }
        Rule best = null;
        for (Rule rule : rulesFor(agent)) {
            if (rule.matches(target) && (best == null || rule.beats(best))) {
                best = rule;
            }
        }
        return best == null || best.allow();
    }

    /** 이 이름에 적용되는 crawl-delay. 없으면 null */
    public Duration crawlDelay(String agent) {
        List<Group> matched = groupsFor(agent);
        return matched.stream()
                .map(Group::crawlDelay)
                .filter(d -> d != null)
                .max(Duration::compareTo)
                .orElse(null);
    }

    private List<Rule> rulesFor(String agent) {
        return groupsFor(agent).stream().flatMap(g -> g.rules().stream()).toList();
    }

    private List<Group> groupsFor(String agent) {
        String wanted = agent.toLowerCase(Locale.ROOT);
        List<Group> named = groups.stream()
                .filter(g -> g.agents().stream().anyMatch(a -> a.equals(wanted)))
                .toList();
        if (!named.isEmpty()) {
            return named;
        }
        return groups.stream().filter(g -> g.agents().contains("*")).toList();
    }

    /** "Googlebot/2.1 (+http://...)"처럼 적혀 있어도 이름 부분만 비교한다. */
    private static String token(String value) {
        String name = value.split("[/\\s]", 2)[0];
        return name.toLowerCase(Locale.ROOT);
    }

    private static Duration seconds(String value) {
        try {
            double seconds = Double.parseDouble(value);
            if (seconds <= 0 || Double.isNaN(seconds)) {
                return null;
            }
            // 터무니없이 긴 값은 1분으로 묶는다(어차피 우리는 한 번에 한두 번만 부른다)
            return Duration.ofMillis((long) (Math.min(seconds, 60) * 1000));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record Group(List<String> agents, List<Rule> rules, Duration crawlDelay) {
    }

    private record Rule(boolean allow, String pattern) {

        boolean matches(String path) {
            boolean anchored = pattern.endsWith("$");
            String body = anchored ? pattern.substring(0, pattern.length() - 1) : pattern;
            return match(body, 0, path, 0, anchored);
        }

        /** 더 구체적인(긴) 규칙이 이긴다. 길이가 같으면 allow가 이긴다. */
        boolean beats(Rule other) {
            if (pattern.length() != other.pattern.length()) {
                return pattern.length() > other.pattern.length();
            }
            return allow && !other.allow;
        }

        private static boolean match(String pattern, int p, String path, int s, boolean anchored) {
            while (p < pattern.length()) {
                char c = pattern.charAt(p);
                if (c == '*') {
                    // 연달은 '*'는 하나로 본다
                    while (p < pattern.length() && pattern.charAt(p) == '*') {
                        p++;
                    }
                    if (p == pattern.length()) {
                        return true;
                    }
                    for (int i = s; i <= path.length(); i++) {
                        if (match(pattern, p, path, i, anchored)) {
                            return true;
                        }
                    }
                    return false;
                }
                if (s >= path.length() || path.charAt(s) != c) {
                    return false;
                }
                p++;
                s++;
            }
            return !anchored || s == path.length();
        }
    }
}
