-- 공 영상 주소 찾기가 HTML 표기(&#x3D;)가 들어간 주소를 놓쳐 '영상 없음'(mp4_url null)으로 저장한 줄을 지운다(이슈 #163).
-- 지우면 다음에 누를 때 다시 묻는다. 영상이 정말 없던 공도 한 번 더 묻을 뿐이라 괜찮다.
delete from play_clip where mp4_url is null;
