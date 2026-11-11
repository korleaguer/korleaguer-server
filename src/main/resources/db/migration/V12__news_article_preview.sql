-- 관련 기사 미리보기(이슈 #75). 출처가 허락하는 만큼만 채운다(news/sources.yml의 preview).
-- lead_text: 피드가 준 기사 첫머리(200자 안팎, 글자만). 마이데일리처럼 첫머리까지만 허락하는 곳
-- image_url: 기사 페이지의 대표 사진(og:image) 원본 주소. 사진 파일은 받지 않는다
-- summary: Claude가 쓴 한국어 요약(3~4줄), summary_model: 요약한 모델
-- enriched_at: 마지막으로 요약을 시도한 시각, enrich_attempts: 시도 횟수(실패하면 몇 번까지만 다시)
alter table news_article add column lead_text varchar(400) null;
alter table news_article add column image_url varchar(1000) null;
alter table news_article add column summary varchar(1200) null;
alter table news_article add column summary_model varchar(50) null;
alter table news_article add column enriched_at datetime(6) null;
alter table news_article add column enrich_attempts int not null default 0;
