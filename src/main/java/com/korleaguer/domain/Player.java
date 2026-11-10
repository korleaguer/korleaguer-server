package com.korleaguer.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.Instant;
import java.time.LocalDate;

@Entity
public class Player {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String nameKo;
    private String nameEn;
    private Long mlbId;
    private String position;
    private String birthPlace;
    private boolean active = true;
    // MLB 선수 필드. 키·몸무게·투타는 받은 값 그대로(6' 0", 196, Left/Right) 두고 응답에서 바꾼다.
    private LocalDate birthDate;
    private String height;
    private String weight;
    private String batsThrows;
    private String jersey;
    private Integer debutYear;
    private Integer age;
    // MLB 공식 Stats API 값. 지금 단계(MLB/MINORS, 모르면 null), MLB가 알려 준 지금 팀 이름, 마지막 확인 시각
    @Enumerated(EnumType.STRING)
    private Level level;
    private String levelTeamName;
    private Instant levelCheckedAt;

    protected Player() {
    }

    public Player(String nameKo) {
        this.nameKo = nameKo;
    }

    /** 명단의 MLB 선수 번호. 명단에서 빠지면 null이다. */
    public void changeMlbId(Long mlbId) {
        this.mlbId = mlbId;
    }

    public void update(String nameEn, String position, String birthPlace, boolean active) {
        this.nameEn = nameEn;
        this.position = position;
        this.birthPlace = birthPlace;
        this.active = active;
    }

    public void updateProfile(LocalDate birthDate, String height, String weight, String batsThrows, String jersey,
                              Integer debutYear, Integer age) {
        this.birthDate = birthDate;
        this.height = height;
        this.weight = weight;
        this.batsThrows = batsThrows;
        this.jersey = jersey;
        this.debutYear = debutYear;
        this.age = age;
    }

    public void updateLevel(Level level, String levelTeamName, Instant checkedAt) {
        this.level = level;
        this.levelTeamName = levelTeamName;
        this.levelCheckedAt = checkedAt;
    }

    public Role role() {
        return Role.fromPosition(position);
    }

    public Long getId() {
        return id;
    }

    public String getNameKo() {
        return nameKo;
    }

    public String getNameEn() {
        return nameEn;
    }

    public String getPosition() {
        return position;
    }

    public Long getMlbId() {
        return mlbId;
    }

    public String getBirthPlace() {
        return birthPlace;
    }

    public boolean isActive() {
        return active;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public String getHeight() {
        return height;
    }

    public String getWeight() {
        return weight;
    }

    public String getBatsThrows() {
        return batsThrows;
    }

    public String getJersey() {
        return jersey;
    }

    public Integer getDebutYear() {
        return debutYear;
    }

    public Integer getAge() {
        return age;
    }

    public Level getLevel() {
        return level;
    }

    public String getLevelTeamName() {
        return levelTeamName;
    }

    public Instant getLevelCheckedAt() {
        return levelCheckedAt;
    }
}
