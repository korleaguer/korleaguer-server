package com.korleaguer.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.time.Instant;

/**
 * 기사 페이지를 401·403·451로 막은 사이트(호스트)와 다시 읽어 볼 시각. 그때까지 그 사이트의 기사 페이지는 받지 않는다
 * (피드는 계속 받는다). 서버를 다시 띄워도 이어지게 DB에 둔다(배포마다 다시 요청하지 않게).
 */
@Entity
public class NewsBlockedHost {

    @Id
    private String host;
    private Instant blockedUntil;

    protected NewsBlockedHost() {
    }

    public NewsBlockedHost(String host, Instant blockedUntil) {
        this.host = host;
        this.blockedUntil = blockedUntil;
    }

    public String getHost() {
        return host;
    }

    public Instant getBlockedUntil() {
        return blockedUntil;
    }
}
