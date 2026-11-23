package com.korleaguer.api;

import com.korleaguer.domain.NewsArticle;
import com.korleaguer.support.TeamNames;

import java.util.List;

/**
 * 기사가 왜 그 구단을 행선지로 거론했나 한 줄(이슈 #169). Claude 요약(줄마다 한 문장)에서 그 구단 이름이 처음 나온 문장을 고른다.
 * 운영 기사로 재 보니(11-16) 거론 90건 중 89건은 요약에 그 문장이 있었고 1건은 제목에만 있었다.
 */
final class LinkedReasons {

    /**
     * @param sentence 요약에서 고른 문장. 없으면 null
     * @param name     sentence(없으면 대표 기사 제목)에서 찾은 구단 이름. 없으면 null
     */
    record Reason(String sentence, String name) {
    }

    private LinkedReasons() {
    }

    /**
     * @param articles 찾아볼 기사(앞이 먼저). 첫 기사는 화면에 보일 대표 기사
     */
    static Reason find(String team, List<NewsArticle> articles) {
        List<String> names = TeamNames.searchNames(team);
        if (names.isEmpty() || articles.isEmpty()) {
            return new Reason(null, null);
        }
        for (NewsArticle a : articles) {
            if (a.getSummary() == null) {
                continue;
            }
            for (String line : a.getSummary().split("\n")) {
                String sentence = line.strip();
                String name = nameIn(sentence, names);
                if (name != null) {
                    return new Reason(sentence, name);
                }
            }
        }
        // 요약에 없으면 화면은 대표 기사 제목을 보인다. 제목에 이름이 있으면 그것만 강조한다
        NewsArticle lead = articles.getFirst();
        String title = lead.getTitleKo() != null ? lead.getTitleKo() : lead.getTitle();
        return new Reason(null, title == null ? null : nameIn(title, names));
    }

    private static String nameIn(String text, List<String> names) {
        for (String name : names) {
            if (text.contains(name)) {
                return name;
            }
        }
        return null;
    }
}
