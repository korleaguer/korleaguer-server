# 포스팅 선배 사진 편집본

홈 MLB 도전 카드의 선배 동그라미 사진(`src/main/resources/photos/posting-{MLB 선수 번호}-{시즌}.jpg`)을 만든다.
MLB 공식 증명사진은 배경 회색이 사진마다 다르고 얼굴이 사진에 꽉 차 있어서, 바로 위 포스팅 선수(KBO 공식 사진, 흰 배경)와 나란히 두면 어색하다.
그래서 배경을 지워 흰 바탕(#FFFFFF)에 놓고, 얼굴 크기·위치를 노시환 KBO 2026 공식 사진과 같게 맞춘 256×256 정사각형으로 저장한다.
화면은 이 사진으로 원을 채운다(노시환 사진과 같은 표시).

- 배경 지우기와 얼굴 찾기는 macOS Vision(사람 전경 마스크·얼굴 사각형)을 쓴다. 그래서 맥에서만 돈다(서버는 만든 사진을 그대로 준다).
- 기준 값(얼굴 높이 49.25%, 얼굴 중심 위에서 57.47%)은 `posting-photo.swift` 맨 위에 있다. 기준 사진을 바꾸면 `measure`로 다시 잰다.

## 새 선배를 넣을 때

`roster/posting.yml` history에 `mlb-id`·`photo-season`·`photo-url`(계약 구단 시절 MLB 공식 사진, 출처 기록)을 적었으면 편집본도 만든다.
편집본이 없으면 공식 사진을 켠 서버는 뜨지 않는다(PhotoSources).

```bash
cd tools/posting-photo
xcrun swiftc -O -o /tmp/posting-photo posting-photo.swift
curl -sS -A 'korleaguer-photo-tool' -o /tmp/original.jpg '<photo-url>'
/tmp/posting-photo cut /tmp/original.jpg ../../src/main/resources/photos/posting-<mlb-id>-<photo-season>.jpg
```

- 원본은 posting.yml의 `photo-url`이다. 인터넷 보관소 주소가 열리지 않으면 운영 서버에 남은 원본(`https://pigeon.ddns.net/photos/mlb-<mlb-id>-<photo-season>`, 2026-11-11까지 받아 둔 것)을 쓴다.
- 만든 사진은 눈으로 확인한다: 흰 배경, 모자 위와 턱 아래에 여유, 얼굴이 가운데.
- 사진의 권리는 MLB·구단에 있다. 배경만 지우고 크기·위치만 맞춘다(얼굴·유니폼은 고치지 않는다).
