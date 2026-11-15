package com.korleaguer.api;

import com.korleaguer.domain.NewsArticle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 뉴스 탭 검색창의 '자주 나온 말'(이슈 #158). 정해 둔 말(MLB 구단·주제) 가운데 최근 기사 묶음의 제목·요약에 많이 나온 것을
 * 센다. 세는 법은 검색(NewsListFinder, 소문자로 바꾼 글자 포함)과 같아서, 알약을 누르면 대개 그 수만큼 나온다(검색은 최근 기사
 * 밖까지 찾으므로 더 많을 수 있다).
 * 운영 기사 93건(11-15)을 세어 정했다: 다저스 19 · FA 18 · 영입 13 · 수비 13 · 트레이드 10 · 계약 8 · 메츠 8.
 */
final class NewsTopics {

    /** 보이는 말 수 */
    static final int SHOWN = 6;
    /** 이보다 적게 나온 말은 보이지 않는다(눌러도 한두 건이면 바로가기로 쓸모가 없다) */
    static final int MIN_COUNT = 3;

    /**
     * 셀 말. 구단은 한국 기사에서 흔히 쓰는 이름(별명·도시가 둘 다 쓰이면 둘 다), 주제는 해외파 소식에 자주 나오는 말.
     * 두 글자 미만은 넣지 않는다(다른 말 속에 우연히 들어가기 쉽다). 'FA'만 예외로 넣었다(가장 많이 찾는 말).
     */
    static final List<String> WORDS = List.of(
            // 주제
            "FA", "포스팅", "계약", "트레이드", "영입", "이적", "연봉", "부상", "수술", "재활", "콜업", "마이너", "방출",
            "논텐더", "직장폐쇄", "골드글러브", "홈런", "데뷔", "스프링캠프", "시범경기", "개막", "올스타", "결승타", "수비",
            "타격", "선발", "불펜",
            // 구단
            "다저스", "메츠", "양키스", "자이언츠", "샌프란시스코", "파드리스", "샌디에이고", "브레이브스", "애틀랜타",
            "필리스", "필라델피아", "파이리츠", "피츠버그", "레드삭스", "보스턴", "컵스", "화이트삭스", "레인저스", "텍사스",
            "애스트로스", "휴스턴", "매리너스", "시애틀", "블루제이스", "토론토", "오리올스", "볼티모어", "탬파베이",
            "가디언스", "클리블랜드", "타이거스", "디트로이트", "트윈스", "미네소타", "로열스", "캔자스시티", "애슬레틱스",
            "에인절스", "다이아몬드백스", "애리조나", "로키스", "콜로라도", "신시내티", "브루어스", "밀워키", "카디널스",
            "세인트루이스", "말린스", "마이애미", "내셔널스", "워싱턴");

    private NewsTopics() {
    }

    /** 묶음들에서 많이 나온 말(많은 순, 같으면 사전 순서) 최대 SHOWN개. MIN_COUNT번 미만은 뺀다 */
    static List<NewsListResponse.Topic> count(List<SameArticles.Group> groups) {
        List<String> texts = groups.stream().map(NewsTopics::text).toList();
        List<NewsListResponse.Topic> out = new ArrayList<>();
        for (String word : WORDS) {
            String needle = word.toLowerCase(Locale.ROOT);
            int n = (int) texts.stream().filter(t -> t.contains(needle)).count();
            if (n >= MIN_COUNT) {
                out.add(new NewsListResponse.Topic(word, n));
            }
        }
        return out.stream()
                .sorted(Comparator.comparingInt(NewsListResponse.Topic::count).reversed())
                .limit(SHOWN)
                .toList();
    }

    /** 묶음의 모든 기사 제목(원제·한국어 제목)과 요약을 소문자로 이은 글(검색이 보는 칸과 같다) */
    private static String text(SameArticles.Group group) {
        StringBuilder sb = new StringBuilder();
        for (NewsArticle a : group.members()) {
            sb.append(a.getTitle()).append('\n');
            if (a.getTitleKo() != null) {
                sb.append(a.getTitleKo()).append('\n');
            }
            if (a.getSummary() != null) {
                sb.append(a.getSummary()).append('\n');
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
