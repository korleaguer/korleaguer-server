package com.korleaguer.api.article;

import com.korleaguer.domain.NewsArticle;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 뉴스 탭 검색창의 '자주 나온 말'(이슈 #158). 정해 둔 말(MLB 구단·주제) 가운데 최근 기사 묶음의 제목에 많이 나온 것을
 * 고른다. 세는 법은 검색(NewsListFinder, mentions)과 같다. 보이는 숫자는 NewsListFinder가 그 말로 검색했을 때의 줄 수
 * (기사 전체)로 바꾼다(이슈 #167).
 * 운영 기사 93건(11-15)을 세어 정했다: 다저스 19 · FA 18 · 영입 13 · 수비 13 · 트레이드 10 · 계약 8 · 메츠 8.
 * 선수를 고르면 그 선수의 지금 구단 이름은 세지 않는다(이슈 #165): 그 선수 기사 대부분에 나와 눌러도 거의 걸러지지 않는다
 * (운영 11-15 김혜성: 다저스 15 · 트레이드 4).
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

    /**
     * 같은 구단의 두 이름(별명·도시). 둘 다 많이 나오면 알약 두 칸이 같은 기사를 가리키므로 많은 쪽만 둔다
     * (예: 이정후 기사는 '샌프란시스코 자이언츠'를 붙여 쓴다)
     */
    private static final List<List<String>> SAME_TEAM = List.of(
            List.of("자이언츠", "샌프란시스코"), List.of("파드리스", "샌디에이고"), List.of("브레이브스", "애틀랜타"),
            List.of("필리스", "필라델피아"), List.of("파이리츠", "피츠버그"), List.of("레드삭스", "보스턴"),
            List.of("레인저스", "텍사스"), List.of("애스트로스", "휴스턴"), List.of("매리너스", "시애틀"),
            List.of("블루제이스", "토론토"), List.of("오리올스", "볼티모어"), List.of("가디언스", "클리블랜드"),
            List.of("타이거스", "디트로이트"), List.of("트윈스", "미네소타"), List.of("로열스", "캔자스시티"),
            List.of("다이아몬드백스", "애리조나"), List.of("로키스", "콜로라도"), List.of("브루어스", "밀워키"),
            List.of("카디널스", "세인트루이스"), List.of("말린스", "마이애미"), List.of("내셔널스", "워싱턴"));

    /**
     * 구단 약어(Team.abbreviation, 쓰는 곳마다 다른 약어도 함께) → WORDS에 있는 그 구단 이름들(별명·도시).
     * 고른 선수의 지금 구단을 뺄 때 쓴다(TeamNames의 약어 표와 같은 약어)
     */
    private static final Map<String, List<String>> TEAM_WORDS = Map.ofEntries(
            Map.entry("ARI", List.of("다이아몬드백스", "애리조나")), Map.entry("AZ", List.of("다이아몬드백스", "애리조나")),
            Map.entry("ATL", List.of("브레이브스", "애틀랜타")),
            Map.entry("BAL", List.of("오리올스", "볼티모어")),
            Map.entry("BOS", List.of("레드삭스", "보스턴")),
            Map.entry("CHC", List.of("컵스")),
            Map.entry("CWS", List.of("화이트삭스")), Map.entry("CHW", List.of("화이트삭스")),
            Map.entry("CIN", List.of("신시내티")),
            Map.entry("CLE", List.of("가디언스", "클리블랜드")),
            Map.entry("COL", List.of("로키스", "콜로라도")),
            Map.entry("DET", List.of("타이거스", "디트로이트")),
            Map.entry("HOU", List.of("애스트로스", "휴스턴")),
            Map.entry("KC", List.of("로열스", "캔자스시티")), Map.entry("KCR", List.of("로열스", "캔자스시티")),
            Map.entry("LAA", List.of("에인절스")),
            Map.entry("LAD", List.of("다저스")),
            Map.entry("MIA", List.of("말린스", "마이애미")),
            Map.entry("MIL", List.of("브루어스", "밀워키")),
            Map.entry("MIN", List.of("트윈스", "미네소타")),
            Map.entry("NYM", List.of("메츠")),
            Map.entry("NYY", List.of("양키스")),
            Map.entry("ATH", List.of("애슬레틱스")), Map.entry("OAK", List.of("애슬레틱스")),
            Map.entry("PHI", List.of("필리스", "필라델피아")),
            Map.entry("PIT", List.of("파이리츠", "피츠버그")),
            Map.entry("SD", List.of("파드리스", "샌디에이고")), Map.entry("SDP", List.of("파드리스", "샌디에이고")),
            Map.entry("SF", List.of("자이언츠", "샌프란시스코")), Map.entry("SFG", List.of("자이언츠", "샌프란시스코")),
            Map.entry("SEA", List.of("매리너스", "시애틀")),
            Map.entry("STL", List.of("카디널스", "세인트루이스")),
            Map.entry("TB", List.of("탬파베이")), Map.entry("TBR", List.of("탬파베이")),
            Map.entry("TEX", List.of("레인저스", "텍사스")),
            Map.entry("TOR", List.of("블루제이스", "토론토")),
            Map.entry("WSH", List.of("내셔널스", "워싱턴")), Map.entry("WSN", List.of("내셔널스", "워싱턴")),
            Map.entry("WAS", List.of("내셔널스", "워싱턴")));

    /** 영문·숫자만으로 된 말(FA 등). 이런 말은 낱말 단위로만 찾는다('DFA'·'fans' 속의 fa는 아니다) */
    private static final Pattern LATIN_WORD = Pattern.compile("[A-Za-z0-9 ]+");

    private NewsTopics() {
    }

    /** 구단 약어의 이름들(WORDS에 있는 것). 모르는 약어나 null이면 빈 목록 */
    static List<String> teamWords(String abbreviation) {
        return abbreviation == null ? List.of() : TEAM_WORDS.getOrDefault(abbreviation, List.of());
    }

    /**
     * 묶음들에서 많이 나온 말(많은 순, 같으면 정해 둔 순서) 최대 SHOWN개. MIN_COUNT번 미만은 뺀다.
     * skip에 든 말(고른 선수의 지금 구단 이름)은 세지 않는다
     */
    static List<NewsTopic> count(List<SameArticles.Group> groups, Collection<String> skip) {
        List<String> texts = groups.stream().map(NewsTopics::text).toList();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String word : WORDS) {
            if (skip.contains(word)) {
                continue;
            }
            int n = (int) texts.stream().filter(t -> mentions(t, word)).count();
            if (n >= MIN_COUNT) {
                counts.put(word, n);
            }
        }
        for (List<String> team : SAME_TEAM) {
            if (counts.containsKey(team.get(0)) && counts.containsKey(team.get(1))) {
                counts.remove(counts.get(team.get(0)) >= counts.get(team.get(1)) ? team.get(1) : team.get(0));
            }
        }
        return counts.entrySet().stream()
                .map(e -> new NewsTopic(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingInt(NewsTopic::count).reversed())
                .limit(SHOWN)
                .toList();
    }

    /**
     * 글에 이 말이 있나(대소문자 상관없이). 한글이 섞인 말은 글자 포함으로, 영문·숫자만으로 된 말은 앞뒤에 다른 영문·숫자가 붙지
     * 않은 것만 센다(FA: 'FA 시장'은 되고 'DFA'·'fans'는 아니다). 검색(NewsListFinder)도 이 규칙으로 한 번 더 거른다
     */
    static boolean mentions(String text, String word) {
        if (text == null || word == null || word.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        String needle = word.toLowerCase(Locale.ROOT);
        if (!LATIN_WORD.matcher(word).matches()) {
            return lower.contains(needle);
        }
        for (int at = lower.indexOf(needle); at >= 0; at = lower.indexOf(needle, at + 1)) {
            boolean before = at > 0 && isLatinOrDigit(lower.charAt(at - 1));
            int end = at + needle.length();
            boolean after = end < lower.length() && isLatinOrDigit(lower.charAt(end));
            if (!before && !after) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLatinOrDigit(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    /**
     * 묶음의 모든 기사 제목(원제·한국어 제목)을 소문자로 이은 글(검색이 보는 칸과 같다). 요약은 넣지 않는다: 요약까지 세면
     * 운영 기사 93건 중 '계약' 45건(제목에 보이는 건 8건)처럼 절반이 걸려 바로가기로 쓸모가 없었다(이슈 #161)
     */
    private static String text(SameArticles.Group group) {
        StringBuilder sb = new StringBuilder();
        for (NewsArticle a : group.members()) {
            sb.append(a.getTitle()).append('\n');
            if (a.getTitleKo() != null) {
                sb.append(a.getTitleKo()).append('\n');
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
