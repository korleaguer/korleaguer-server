package com.korleaguer.news;

import com.korleaguer.domain.NewsDestination;

import java.util.List;
import java.util.Optional;

/**
 * 기사가 다루는 선수의 행선지(다음 MLB 소속) 후보로 거론된 구단을 고른다(NewsTagger가 부른다). API 키가 없으면 OFF다.
 * 키워드로 구단 이름을 세면 틀린다('워싱턴 코치'는 사람, 애틀랜타는 김하성 이야기). 그래서 글을 읽고 고른다.
 */
public interface DestinationTagger {

    DestinationTagger OFF = new DestinationTagger() {
        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public Optional<Tagging> tag(String player, String title, String text) {
            return Optional.empty();
        }

        @Override
        public String version() {
            return null;
        }
    };

    boolean enabled();

    /**
     * @param player 기사가 다루는 선수의 한글 이름
     * @param title  기사 제목(영어 기사면 한국어 제목과 원래 제목)
     * @param text   기사 요약(없으면 기사 앞부분). 비어 있을 수 있다
     * @return 거론된 구단(한 구단 한 번, 없으면 빈 목록)과 이 선수가 주인공인 기사인지. 답을 읽을 수 없거나 거절이면 empty
     * @throws Summarizer.Failure AI 서비스가 답하지 못했을 때(기사 탓이 아니다. 다음 차례에 다시)
     */
    Optional<Tagging> tag(String player, String title, String text);

    /**
     * 기사 한 건을 본 결과.
     *
     * @param aboutPlayer 이 선수가 주인공인 기사인가(이 선수의 MLB 도전·계약·소속·평가를 다룬다). 다른 이야기에 이름만
     *                    곁들여 나오면 false. 답에 없으면 null(이슈 #129)
     */
    record Tagging(List<Destination> destinations, Boolean aboutPlayer) {
    }

    /** 고른 모델과 지시문 판(기사마다 적는다. 바뀌면 다시 고른다) */
    String version();

    /** 거론된 구단 하나(MLB 대표 약어)와 방향 */
    record Destination(String team, NewsDestination.Stance stance) {
    }
}
