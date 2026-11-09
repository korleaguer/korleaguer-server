package com.korleaguer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** MLB 선수. currentTeam은 hydrate=currentTeam으로 받을 때만 있고, 소속이 없으면(FA 등) null이다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MlbPerson(Long id, String fullName, MlbTeam currentTeam) {
}
