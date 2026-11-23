-- 우리가 호스팅하는 선수 사진(이슈 #51). 처음 요청되거나 주기 작업 때 원본(MLB 사진 서버, KBO 구단 사진 사본 등)에서
-- 한 번 받아 둔다. 그 뒤에는 이 저장본을 주고, 7일마다 조건부 요청(etag·last_modified)으로 원본이 바뀌었는지만 묻는다.
-- photo_key: 사진 이름(mlb-808982, kbo-si-hwan-roh), source_url: 받은 원본 주소(바뀌면 새로 받는다)
-- sha256: 본문 해시(같은 사진이면 다시 쓰지 않는다, 화면 ETag), checked_at: 원본에 마지막으로 물어본 시각
create table photo (
    photo_key     varchar(60)   not null primary key,
    source_url    varchar(1000) not null,
    content_type  varchar(50)   not null,
    data          mediumblob    not null,
    sha256        varchar(64)   not null,
    etag          varchar(200),
    last_modified varchar(100),
    fetched_at    datetime(6)   not null,
    checked_at    datetime(6)   not null
);
