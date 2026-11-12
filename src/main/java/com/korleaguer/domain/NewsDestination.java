package com.korleaguer.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * 기사 한 건에서 '그 기사가 다루는 선수의 행선지로 거론된 MLB 구단' 하나(news.NewsTagger가 AI로 고른다).
 * 홈 MLB 도전 카드의 '행선지로 거론된 구단'이 구단별로 센다(api.PostingFinder). 한 기사에 같은 구단은 한 줄이다.
 */
@Entity
public class NewsDestination {

    /** 기사가 그 구단을 어떻게 말했나 */
    public enum Stance {
        /** 갈 수 있다·관심 있다·잘 맞는다는 거론 */
        LINKED,
        /** 필요 없다·관심 없다·가지 않을 것이라는 전망 */
        DOUBTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long articleId;
    /** MLB 대표 약어(support.MlbTeams) */
    private String team;
    @Enumerated(EnumType.STRING)
    private Stance stance;

    protected NewsDestination() {
    }

    public NewsDestination(Long articleId, String team, Stance stance) {
        this.articleId = articleId;
        this.team = team;
        this.stance = stance;
    }

    public Long getId() {
        return id;
    }

    public Long getArticleId() {
        return articleId;
    }

    public String getTeam() {
        return team;
    }

    public Stance getStance() {
        return stance;
    }
}
