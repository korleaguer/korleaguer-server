package com.korleaguer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.Instant;

/**
 * 한 선수의 한 경기 상세(타석별 기록·하이라이트 영상)를 응답 모양 JSON으로 둔 줄. (playerId, gamePk)마다 한 줄이다.
 * complete면 다시 받지 않는다(끝난 경기를 시작 하루 뒤에 받았을 때. 그 전에는 하이라이트가 덜 올라왔을 수 있다).
 */
@Entity
public class PlayerGameDetail {

    /** payload 칸 길이(varchar). 넘으면 부르는 쪽이 영상을 덜어 넣는다 */
    public static final int MAX_PAYLOAD_LENGTH = 15000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private long playerId;
    private long gamePk;

    @Column(length = MAX_PAYLOAD_LENGTH)
    private String payload;

    private boolean complete;
    private Instant fetchedAt;

    protected PlayerGameDetail() {
    }

    public PlayerGameDetail(long playerId, long gamePk) {
        this.playerId = playerId;
        this.gamePk = gamePk;
    }

    public void update(String payload, boolean complete, Instant fetchedAt) {
        this.payload = payload;
        this.complete = complete;
        this.fetchedAt = fetchedAt;
    }

    public Long getId() {
        return id;
    }

    public long getPlayerId() {
        return playerId;
    }

    public long getGamePk() {
        return gamePk;
    }

    public String getPayload() {
        return payload;
    }

    public boolean isComplete() {
        return complete;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }
}
