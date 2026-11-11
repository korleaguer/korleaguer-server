package com.korleaguer.news;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * korleaguer.news.* 관련 기사 수집 설정(출처 목록은 resources/news/sources.yml).
 *
 * @param enabled      스케줄 수집을 켤지. 꺼도 홈은 DB에 있는 기사를 그대로 보여 준다
 * @param cron         깨어나는 주기(UTC). 포스팅 협상 기간이 아니면 깨어나도 아무 요청도 하지 않는다
 * @param userAgent    우리가 누군지 밝히는 값. 브라우저인 척하지 않는다
 * @param robotsAgents robots.txt에서 확인할 이름. 하나라도 막혀 있으면 받지 않는다(우리 이름과 Claude 계열 이름)
 * @param sources      기사 제목을 받을 피드(RSS·Atom). robots.txt가 허용한 곳만 적는다
 */
@ConfigurationProperties("korleaguer.news")
public record NewsProperties(boolean enabled, String cron, String userAgent, List<String> robotsAgents,
                             List<Source> sources) {

    public NewsProperties {
        robotsAgents = robotsAgents == null ? List.of() : List.copyOf(robotsAgents);
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    /**
     * 피드 하나.
     *
     * @param publisher 화면에 쓰는 언론사 이름
     * @param feedUrl   RSS·Atom 주소
     * @param zone      발행 시각에 시간대 표시가 없을 때 쓸 시간대(비우면 Asia/Seoul)
     * @param subject   선수 한 명만 다루는 피드면 그 선수 한글 이름. 그 피드의 기사는 제목에 이름이 없어도 그 선수 기사다
     * @param interval  이 피드를 다시 받는 최소 간격(비우면 깨어날 때마다). 피드가 크거나 오래 쌓이는 곳은 길게 둔다
     * @param preview   화면에서 제목 아래 무엇을 보여 줄지. lead: 피드가 주는 기사 첫머리(200자 안팎),
     *                  ai: 기사 페이지를 한 번 받아 대표 사진 주소와 Claude 한국어 요약, 비우면 제목만.
     *                  언론사 이용약관이 허락하는 만큼만 적는다(news/sources.yml 머리말)
     */
    public record Source(String publisher, String feedUrl, String zone, String subject, Duration interval,
                         String preview) {

        public static final String LEAD = "lead";
        public static final String AI = "ai";

        public boolean lead() {
            return LEAD.equals(preview == null ? null : preview.trim());
        }

        public boolean ai() {
            return AI.equals(preview == null ? null : preview.trim());
        }
    }
}
