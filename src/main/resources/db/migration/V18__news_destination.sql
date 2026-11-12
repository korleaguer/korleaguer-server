-- 기사마다 '이 선수의 행선지로 거론된 MLB 구단'과 방향(이슈 #115). 홈 MLB 도전 카드의 '행선지로 거론된 구단'이 센다.
-- team: MLB 대표 약어(support.MlbTeams, 예: NYM), stance: LINKED(갈 수 있다·관심 있다는 거론) · DOUBTED(필요 없다·안 갈 것이라는 전망)
-- 기사를 지우면(언론사 요청) 함께 지운다. 새 표·새 칸(기본값 있음)이라 블루-그린 배포 중 옛 API와 겹쳐도 안전하다.
create table news_destination (
    id         bigint      not null auto_increment primary key,
    article_id bigint      not null,
    team       varchar(5)  not null,
    stance     varchar(10) not null,
    constraint fk_news_destination_article foreign key (article_id) references news_article (id) on delete cascade,
    constraint uk_news_destination_article_team unique (article_id, team)
);

-- destinations_at: 구단을 고른 시각(고를 구단이 없었어도 적는다), destinations_model: 고른 모델과 지시문 판(예: claude-haiku-5-5/d1),
-- destination_attempts: 고르지 못한 횟수(기사 탓인 실패만, 몇 번까지만 다시 해 본다)
alter table news_article add column destinations_at datetime(6) null;
alter table news_article add column destinations_model varchar(50) null;
alter table news_article add column destination_attempts int not null default 0;
