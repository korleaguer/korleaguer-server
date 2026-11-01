package com.korleaguer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** balldontlie MLBPlayer */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record BdlPlayer(
        Long id,
        String firstName,
        String lastName,
        String fullName,
        String position,
        Boolean active,
        String birthPlace,
        String dob,
        String batsThrows,
        Integer debutYear,
        String jersey,
        Integer age,
        String height,
        String weight,
        BdlTeam team) {
}
