package com.korleaguer.api;

import com.korleaguer.domain.NewsArticle;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 언론사만 다른 같은 기사(전재)를 목록에서 한 줄로 묶는다(사용자가 고른 '같은 기사 묶기', 11-12, 이슈 #113).
 * 같은 기사: 발행 시각이 NEAR 안이고, 제목 글자(한글·영문·숫자)가 같거나 기사 첫머리(맨 앞 통신사·기자 표시를 뗀 것)의
 * 3글자 조각이 LEAD_OVERLAP 넘게 겹친다. 운영 기사 37건으로 정했다: 제목이 같은 쌍 4개(첫머리 겹침 0.49~0.52)와 연합뉴스
 * 글을 제목만 바꿔 실은 노컷뉴스·SBS(0.48)는 묶이고, 같은 소식을 각자 쓴 연합뉴스·뉴시스(0.39)는 묶이지 않는다.
 * 묶인 쌍은 모두 같은 날 2시간 안에 나왔다.
 */
final class SameArticles {

    /** 첫머리 3글자 조각이 이만큼(자카드) 넘게 겹치면 같은 기사 */
    static final double LEAD_OVERLAP = 0.45;
    /** 첫머리가 이보다 짧으면(글자만 센다) 견주지 않는다. 짧은 글은 우연히 겹치기 쉽다 */
    static final int MIN_LEAD_LETTERS = 40;
    /**
     * 발행 시각이 이보다 멀면 같은 글로 보지 않는다. 연재 제목처럼 다른 날 같은 제목으로 나온 새 기사가 옛 기사 뒤에 숨지 않고,
     * 잘못 이어진 한 쌍이 먼 기사들까지 한 묶음으로 끌어오지 않게 한다
     */
    static final Duration NEAR = Duration.ofDays(2);

    private static final Pattern NOT_LETTER = Pattern.compile("[^\\p{IsHangul}\\p{IsLatin}\\p{N}]");
    /** 맨 앞 출처 표시: (서울=연합뉴스), [스포츠조선 기자], 【…】 */
    private static final Pattern SOURCE_TAG = Pattern.compile("^\\s*[\\[(【][^\\])】]{0,40}[\\])】]\\s*");
    /** 맨 앞 기자 표시: 김경윤 기자 = */
    private static final Pattern REPORTER = Pattern.compile("^\\s*[가-힣]{2,4}\\s*(?:기자|특파원)\\s*=\\s*");

    private SameArticles() {
    }

    /**
     * 묶음 하나.
     *
     * @param article 목록에 보일 대표 기사: 요약이 있는 기사 가운데 가장 먼저 낸 것(대개 원문을 쓴 언론사), 요약이 없으면 가장 먼저 낸 것
     * @param alsoBy  같은 글을 실은 다른 언론사(낸 순, 대표 언론사는 빼고)
     * @param members 묶인 기사 모두(대표 포함)
     */
    record Group(NewsArticle article, List<String> alsoBy, List<NewsArticle> members) {

        boolean contains(Long id) {
            return members.stream().anyMatch(a -> Objects.equals(a.getId(), id));
        }
    }

    /** 기사 목록(어떤 순서든)을 같은 기사끼리 묶는다. 묶음은 대표 기사의 늦은 순이다 */
    static List<Group> group(List<NewsArticle> articles) {
        List<NewsArticle> list = List.copyOf(articles);
        int[] parent = new int[list.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        List<Instant> times = list.stream().map(NewsArticle::getPublishedAt).toList();
        List<String> titles = list.stream().map(a -> letters(a.getTitle())).toList();
        List<Set<String>> leads = list.stream().map(a -> trigrams(a.getLeadText())).toList();
        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                if (near(times.get(i), times.get(j))
                        && same(titles.get(i), leads.get(i), titles.get(j), leads.get(j))) {
                    parent[root(parent, i)] = root(parent, j);
                }
            }
        }
        Map<Integer, List<NewsArticle>> byRoot = new LinkedHashMap<>();
        for (int i = 0; i < list.size(); i++) {
            byRoot.computeIfAbsent(root(parent, i), k -> new ArrayList<>()).add(list.get(i));
        }
        Comparator<NewsArticle> firstPublished = Comparator.comparing(NewsArticle::getPublishedAt)
                .thenComparing(a -> a.getId() == null ? Long.MAX_VALUE : a.getId());
        return byRoot.values().stream()
                .map(members -> {
                    List<NewsArticle> ordered = members.stream().sorted(firstPublished).toList();
                    NewsArticle lead = ordered.stream().filter(a -> a.getSummary() != null).findFirst()
                            .orElse(ordered.getFirst());
                    Set<String> others = new LinkedHashSet<>();
                    ordered.stream().filter(a -> a != lead).map(NewsArticle::getPublisher)
                            .filter(p -> !p.equals(lead.getPublisher())).forEach(others::add);
                    return new Group(lead, List.copyOf(others), ordered);
                })
                .sorted(Comparator.comparing((Group g) -> g.article().getPublishedAt()).reversed()
                        .thenComparing(g -> g.article().getId() == null ? Long.MIN_VALUE : g.article().getId(),
                                Comparator.reverseOrder()))
                .toList();
    }

    /** 두 기사가 같은 글인지(NEAR 안에 나왔고, 제목 글자가 같거나 첫머리가 거의 같다) */
    static boolean same(NewsArticle a, NewsArticle b) {
        return near(a.getPublishedAt(), b.getPublishedAt())
                && same(letters(a.getTitle()), trigrams(a.getLeadText()), letters(b.getTitle()), trigrams(b.getLeadText()));
    }

    private static boolean near(Instant a, Instant b) {
        return Duration.between(a, b).abs().compareTo(NEAR) <= 0;
    }

    private static boolean same(String titleA, Set<String> leadA, String titleB, Set<String> leadB) {
        if (!titleA.isEmpty() && titleA.equals(titleB)) {
            return true;
        }
        if (leadA.isEmpty() || leadB.isEmpty()) {
            return false;
        }
        Set<String> common = new HashSet<>(leadA);
        common.retainAll(leadB);
        Set<String> all = new HashSet<>(leadA);
        all.addAll(leadB);
        return (double) common.size() / all.size() > LEAD_OVERLAP;
    }

    private static int root(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    /** 견줄 글자: 한글·영문·숫자만, 소문자(부호·공백·따옴표 모양이 달라도 같은 제목으로 본다) */
    static String letters(String text) {
        return text == null ? "" : NOT_LETTER.matcher(text).replaceAll("").toLowerCase(Locale.ROOT);
    }

    /** 첫머리의 3글자 조각. 맨 앞 출처·기자 표시는 뗀다(같은 글도 실은 곳마다 다르다). 너무 짧으면 빈 집합 */
    static Set<String> trigrams(String lead) {
        if (lead == null) {
            return Set.of();
        }
        String text = REPORTER.matcher(SOURCE_TAG.matcher(lead).replaceFirst("")).replaceFirst("");
        String letters = letters(text);
        if (letters.codePointCount(0, letters.length()) < MIN_LEAD_LETTERS) {
            return Set.of();
        }
        int[] points = letters.codePoints().toArray();
        Set<String> grams = new HashSet<>();
        for (int i = 0; i + 3 <= points.length; i++) {
            grams.add(new String(points, i, 3));
        }
        return grams;
    }
}
