package com.korleaguer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** balldontlie MLBTeam */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record BdlTeam(
        Long id,
        String abbreviation,
        String displayName,
        String shortDisplayName,
        String name,
        String location,
        String league,
        String division) {
}
