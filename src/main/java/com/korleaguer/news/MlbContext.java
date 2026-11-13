package com.korleaguer.news;

import com.korleaguer.support.MlbTeams;
import com.korleaguer.support.TeamNames;

import java.util.ArrayList;
import java.util.List;

/**
 * 국내 기사 제목이 MLB 이야기인지(이슈 #127). 포스팅 선수는 아직 KBO 선수라 이름만으로 모으면 'KIA가 필요한 FA는 …
 * 노시환급 초대형계약' 같은 KBO 기사가 섞인다(사용자 11-13: "포스팅 관련 기사가 아니잖아").
 * 제목에 MLB 쪽 말(MLB·ML·메이저·빅리그·포스팅·美·미국·외신·MLB 구단 이름 등)이 하나라도 있으면 MLB 이야기로 본다.
 * 운영 노시환 기사 41건에 돌려 KBO FA 기사·일본 선수 기사 2건만 빠지고 관련 기사는 다 남는 것을 확인했다.
 * 명단 선수(MLB 선수)에게는 쓰지 않는다(김하성 '퀄리파잉 오퍼'·'FA 랭킹' 같은 MLB 기사 6건이 빠졌다).
 * 영어 매체(preview: ai)는 MLB 매체라 쓰지 않는다.
 */
public final class MlbContext {

    private static final List<String> WORDS = words();

    private MlbContext() {
    }

    private static List<String> words() {
        List<String> words = new ArrayList<>(List.of("MLB", "ML", "메이저", "빅리그", "포스팅", "美", "미국", "미 ", "외신", "30개 구단",
                "월드시리즈", "내셔널리그", "아메리칸리그", "뉴욕", "LA", "SF", "SD"));
        // 구단 한글 이름(뉴욕 메츠)과 끝말(메츠). 제목은 '메츠행'처럼 끝말만 쓰는 일이 많다
        for (String abbreviation : MlbTeams.abbreviations()) {
            String korean = TeamNames.korean(abbreviation, abbreviation);
            words.add(korean);
            words.add(korean.substring(korean.lastIndexOf(' ') + 1));
        }
        return List.copyOf(words);
    }

    private static final java.util.regex.Pattern HANGUL = java.util.regex.Pattern.compile("\\p{IsHangul}");

    /** 제목에 MLB 쪽 말이 하나라도 있나. 한글이 없는 제목(영어 MLB 매체 기사)은 MLB 이야기로 본다 */
    public static boolean mentions(String title) {
        if (title == null) {
            return false;
        }
        return !HANGUL.matcher(title).find() || WORDS.stream().anyMatch(title::contains);
    }
}
