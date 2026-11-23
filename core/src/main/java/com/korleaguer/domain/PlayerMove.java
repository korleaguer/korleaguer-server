package com.korleaguer.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

import java.time.LocalDate;

/** MLB 이동 기록 한 줄(콜업·강등·부상자 명단 등). MLB 이동 기록 번호가 기본키다. 팀 단계는 모르면 null이다. */
@Entity
public class PlayerMove {

    private static final int MAX_DESCRIPTION_LENGTH = 1000;

    @Id
    private Long mlbTransactionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "player_id")
    private Player player;

    private LocalDate moveDate;
    private String typeCode;
    private String typeDesc;
    private String description;

    private Long fromTeamId;
    private String fromTeamName;
    @Enumerated(EnumType.STRING)
    private Level fromLevel;

    private Long toTeamId;
    private String toTeamName;
    @Enumerated(EnumType.STRING)
    private Level toLevel;

    protected PlayerMove() {
    }

    public PlayerMove(Long mlbTransactionId, Player player) {
        this.mlbTransactionId = mlbTransactionId;
        this.player = player;
    }

    public void update(Player player, LocalDate moveDate, String typeCode, String typeDesc, String description,
                       Long fromTeamId, String fromTeamName, Level fromLevel,
                       Long toTeamId, String toTeamName, Level toLevel) {
        this.player = player;
        this.moveDate = moveDate;
        this.typeCode = typeCode;
        this.typeDesc = typeDesc;
        this.description = description != null && description.length() > MAX_DESCRIPTION_LENGTH
                ? description.substring(0, MAX_DESCRIPTION_LENGTH)
                : description;
        this.fromTeamId = fromTeamId;
        this.fromTeamName = fromTeamName;
        this.fromLevel = fromLevel;
        this.toTeamId = toTeamId;
        this.toTeamName = toTeamName;
        this.toLevel = toLevel;
    }

    public Long getMlbTransactionId() {
        return mlbTransactionId;
    }

    public Player getPlayer() {
        return player;
    }

    public LocalDate getMoveDate() {
        return moveDate;
    }

    public String getTypeCode() {
        return typeCode;
    }

    public String getTypeDesc() {
        return typeDesc;
    }

    public String getDescription() {
        return description;
    }

    public Long getFromTeamId() {
        return fromTeamId;
    }

    public String getFromTeamName() {
        return fromTeamName;
    }

    public Level getFromLevel() {
        return fromLevel;
    }

    public Long getToTeamId() {
        return toTeamId;
    }

    public String getToTeamName() {
        return toTeamName;
    }

    public Level getToLevel() {
        return toLevel;
    }
}
