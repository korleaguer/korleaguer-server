-- 포스팅 선수 관련 기사(이슈 #47). 언론사 피드에서 제목·링크·언론사·발행 시각만 받는다(본문·사진·요약은 받지 않는다).
-- subject: 기사가 다루는 포스팅 선수의 한글 이름(roster/posting.yml의 name-ko). 포스팅 선수는 아직 명단에 없어 선수 번호가 없다.
-- url: 원문 주소(추적용 utm_* 매개변수와 #조각은 뺀 것), url_hash: 그 주소의 SHA-256(긴 주소에 고유 제약을 걸려고)
-- published_at: 피드의 발행 시각(UTC). 피드에 없으면 처음 받은 시각
create table news_article (
    id           bigint        not null auto_increment primary key,
    subject      varchar(50)   not null,
    url          varchar(1000) not null,
    url_hash     varchar(64)   not null,
    title        varchar(300)  not null,
    publisher    varchar(50)   not null,
    published_at datetime(6)   not null,
    fetched_at   datetime(6)   not null,
    constraint uk_news_article_subject_url unique (subject, url_hash)
);

create index ix_news_article_subject_published on news_article (subject, published_at);
