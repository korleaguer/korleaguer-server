package com.korleaguer.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.LocalDate;

@Entity
public class Player {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String nameKo;
    private String nameEn;
    private String position;
    private Long bdlPlayerId;
    private String birthPlace;
    private boolean active = true;
    // balldontlie 선수 필드. 키·몸무게·투타는 받은 원문 그대로 두고 응답에서 바꾼다.
    private LocalDate birthDate;
    private String height;
    private String weight;
    private String batsThrows;
    private String jersey;
    private Integer debutYear;
    private Integer age;

    protected Player() {
    }

    public Player(String nameKo) {
        this.nameKo = nameKo;
    }

    public void update(String nameEn, String position, Long bdlPlayerId, String birthPlace, boolean active) {
        this.nameEn = nameEn;
        this.position = position;
        this.bdlPlayerId = bdlPlayerId;
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

    public Long getBdlPlayerId() {
        return bdlPlayerId;
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
}
