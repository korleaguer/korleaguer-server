package com.korleaguer.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.time.Instant;

/**
 * 공 하나 영상 주소(Baseball Savant). playId는 MLB playByPlay의 공 번호(UUID)다.
 * mp4Url이 null이면 받았을 때 영상이 없었다는 뜻이다(나중에 올라올 수 있어 시간이 지나면 다시 묻는다).
 */
@Entity
public class PlayClip {

    @Id
    private String playId;

    private String mp4Url;
    private Instant fetchedAt;

    protected PlayClip() {
    }

    public PlayClip(String playId) {
        this.playId = playId;
    }

    public void update(String mp4Url, Instant fetchedAt) {
        this.mp4Url = mp4Url;
        this.fetchedAt = fetchedAt;
    }

    public String getMp4Url() {
        return mp4Url;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }
}
