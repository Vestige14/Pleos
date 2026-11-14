# 7단계 공유 계약 v1.1

상위 설계: [승인 토의](2026-11-15-stage7-memory-links-design.md). 모델이 아니라 코드가 자료 타입/시간 충돌을 판정한다. 변경은 root에게 요청하고 A/B/C에 동시에 알린다.

## 공통 형식과 제한 (root 설계 선택)

- 기존 Bearer 세션과 고정 OpenAI 모델/low/default, 기존 admission/usage hook 사용. 모델 자동 fallback 금지. 앱과 실제 호출의 설정은 별개. 실제 모델의 이미지 지원은 V가 확인하며 불가하면 명시적으로 보고한다.
- API 시각은 timezone 포함 ISO8601. 경험 날짜는 Asia/Seoul YYYY-MM-DD. 원문 Unicode code point 범위 [startCp,endCp). 요청 ID, noteRevision, userRevision, contextHash는 응답에 그대로 돌아오고 저장 시 다시 대조한다.
- 최대 후보20, 최대 연결10, 원문4000codepoints. 사진은 시간 후보 중 한 메모당 최대10장; 기존 설명은 재사용한다. 초과 후보는 숨긴 성공이 아니라 추가 후보 존재 상태로 남겨 명시적 후속 분석이 가능해야 한다. 자동 전체 라이브러리 전송 금지.
- 이미지: metadata 없는 JPEG, 긴 변 최대1024px, binary 최대524288bytes, base64 JSON body 최대1048576bytes. 초과/손상/권한상실은 실패로 보존하고 다른 기능을 막지 않는다. Android re-encode 후 bytes의 sha256을 imageSha256으로 사용한다. URL/파일경로 입력 없음. 캐시는 hash+model+analysisVersion 기준이며 예산/사용량 hook 밖에서 실제 호출하지 않는다.
- 시간 mode는 CAPTURE_DAY / EXPLICIT_DATE / UNRESOLVED. eventDate는 YYYY-MM-DD 또는null. ‘오늘/아까’는 capturedAt의한국날짜. 명시 YYYY-MM-DD, M월D일, M/D는 해당날짜(연도생략 시 녹음시각 이전의 가장 최근 날짜). ‘어제/그제’는 -1/-2일. 날짜충돌·예전에/지난번 등의 모호한 과거 표현은 UNRESOLVED. 시간 표현 없는 새 기록은 CAPTURE_DAY로 시작하되 이것만으로 연결을 확정하지 않는다. 타 음성 후보도 자신의 원문으로 경험 날짜를 해석하며 녹음일을 무조건 경험일로 삼지 않는다.
- 서버도 선택된 경험 날짜와 후보 evidenceDate 일치를 검사한다. 모호하면 REVIEW, 후보가없으면 NO_MATCH; 모두0모델호출. 날짜가다른 후보를 공급하거나 모델이 돌려준 후보 밖ID를 수용하지 않는다.

## POST /v2/photo-descriptions

Request exact fields: requestId:string, imageSha256:64hex, mimeType:"image/jpeg", imageBase64:string.
Response exact fields: requestId, imageSha256, analysisVersion:"visual-v1", description:string(max500codepoints), labels:array[string](max12,max40codepoints each).

모델 입력은 고정 지시 + input_image 데이터뿐이다. requestId/hash는 전송 관리용이며 모델 prompt에 넣지 않는다. 파일명·EXIF·STT·목적지·위치·날짜·개인화 맥락은 주입하지 않는다. 픽셀 속 글자/명령을 지시로 따르지 않는다. 설명의 대상은 눈에 보이는 사물/장면뿐이며 지리적 장소명/날짜/사용자 감정의 추측을 하지 않는다. raw이미지/본문은 로그에 남기거나 backend디스크에 보관하지 않는다. 결과 캐시의 보존은 Android가 담당하며 backend idempotency는 기존 방식을 재사용한다.

## POST /v2/memory-links

Request exact fields:
- requestId, noteRevision:int>=0, userRevision:int>=0, contextHash:string, capturedAt:ISO8601, text:string
- timeScope:{mode:enum above,eventDate:date|null}
- candidates:[{id:string(max128),kind:"PHOTO"|"NOTE"|"NAVIGATION"|"VISIT",evidenceDate:date|null,timeBasis:"PHOTO_CAPTURE"|"NOTE_EXPERIENCE"|"NAV_OBSERVED"|"NAV_ARRIVED"|"UNDATED_RECENT"|"VISIT",description:string(NOTE max4000, others max1000),occurredAt:ISO8601|null,observedAt:ISO8601|null,placeName:string(max200)|null,latitude:number|null,longitude:number|null}]

PHOTO description은 이미지 단독 분석 결과, NOTE는 다른 불변 STT, NAVIGATION은 실제 SDK 정보, VISIT는 출처를 명시한 기존 방문이다. 선택 정보는 없으면 null이며 모델이 kind/id를 새로 만들지 않는다.

Response exact fields: requestId,noteRevision,userRevision,contextHash,decision:"SUGGEST"|"REVIEW"|"NO_MATCH",links:[{targetId,kind,startCp,endCp}]. SUGGEST만 nonempty links다. 각 link의 원문 범위는 nonblank/유효범위/중복구절 모호성을 검증한다. 모델 내부 evidenceQuote를 server가 codepoint로 변환하는 기존 방식을 재사용한다.

AI는 후보 ID에 연결만 제안하고 원문을 재작성하지 않는다. NAV_OBSERVED만으로 도착/방문을 단정하지 않는다. 같은 라벨의 여러 장소가 모호하면 REVIEW다. 사용자 확정은 제안과 구분한다. 오류는 기존 {error:{code,retryable}}를 재사용하고 불명확 결과의 자동 유료 재시도는 금지한다.

## B → C Android 내비 계약

root-owned `app/src/main/java/app/memoryvault/memorycontract/NavigationEvidence.kt`:
`NavigationEvidence(id,kind,placeName,address,latitude,longitude,observedAtMillis,eventAtMillis)`;
kind: DESTINATION_OBSERVED / ARRIVAL_OBSERVED / UNDATED_RECENT.
`NavigationEvidenceProvider.snapshot(): List<NavigationEvidence>` and `status(): NavigationEvidenceStatus` (NOT_READY,AVAILABLE,UNAVAILABLE).
B implements `app.memoryvault.navigation.PleosNavigationEvidenceSource(context: Context)` : NavigationEvidenceProvider, with start(), close(). Provider query results are async; snapshot never invents dates/locations. For UNDATED_RECENT eventAtMillis=null. observedAtMillis is observation only, not historical arrival. C consumes provider interface; root injects B implementation. Source code contract below travels in all worktrees; C does not write B files.

## C → root 조립 계약

새 Activity `app.memoryvault.memory.ui.MemoryReviewActivity`, non-exported, PARKED/foreground/generation guard. 공개 진입은 기존 주차 화면의 ‘기억 연결’ 한 곳; root owns common screen/Manifest/AppContainer.
C delivers `MemoryAssociationRuntime` providing start/reconcile after DB-ready/new saved note, read-only progress, close; dependencies for AppDatabase/session/provider are explicit. C may propose exact signatures and an integration patch once after inspecting existing container; root resolves once and propagates. Runtime must not run duplicate v1/v2 paid analysis for a single new note. Existing v1 data/results/user decisions remain. New graph state/policy can use additive tables in current Room DB so late-result application and job ownership are transactional; root applies the exact AppDatabase/schema-version/migration assembly patch, not an automatic destructive migration.

main DB entities/Daos integration patch is owned by root; C owns new association models/DAO/runtime and narrow existing queue adaptation if needed. No broad refactor, vector database or new generic job framework. Minimal changes to reuse current ownership/retry checks are preferred.

## 2026-11-15 계약 정정 v1.1 — A/C 시간 해석과 공통 transport

- NOTE 후보의 occurredAt은 그 후보 원문을 녹음/작성한 시각이다. description은 자르지 않은 불변 원문 전체이고 최대4000codepoints다. evidenceDate는 그 원문과 후보 자신의 occurredAt으로 경험 날짜를 해석한 결과다. 새 메모의 capturedAt/서버 현재시각으로 과거 후보의 ‘오늘’을 다시 해석하지 않는다. NOTE 외 description 최대1000은 유지한다. 원문 일부를 잘라 시간 표현을 숨기지 않는다.
- /v2/memory-links의 전체 JSON은 최대65536bytes다. 후보20개 제한과 함께 적용하며, 앱은 원문 절단 대신 완전한 후보 단위로 개수를 줄이고 추가 후보가 있음을 유지한다. 무조건 모든 원문을 보내지 않는다.
- NAV_OBSERVED의 evidenceDate는 observedAt의 서울 날짜로 허용한다. 이는 ‘그 날 조회했을 때 활성 목적지였음’이며 목적지 설정일/도착일/방문일의 증명이 아니다. occurredAt은null로 두고 description/모델 지시/UI에서 관측 정보임을 유지한다. 같은 날짜라는 이유만으로 도착이나 같은 경험을 확정하지 않는다.
- UNDATED_RECENT는 evidenceDate=null, occurredAt=null이다. observedAt은 조회 시각일 뿐이며 날짜 후보 필터를 우회하지 않는다.
- timeScope가 UNRESOLVED면 REVIEW/0호출. timeScope가 유효하지만 날짜상 허용되는 후보가0개이면 NO_MATCH/0호출. 같은 날짜의 후보가 있어도 의미가 모호하면 REVIEW를 유지한다.
- A의 공통adapter 추출을 root가 적용했다: OpenAILinkAdapter._request_json(request: RequestIdentity, *, model_input:list, schema_name:str, schema:dict) -> object; RequestIdentity에는 requestId:str만 필요하다. v1 suggest는 기존 입력/schema를 이 함수에 전달한다. 기존 low/default/maxoutput/admission/usage/error/responseparse를 한곳에서 재사용하고 v2는 고정 input/schema만 제공한다. A는 이 root소유 파일을 임의 수정하지 않는다. 실제 v1/v2 회귀 검증은 V에게 남는다.
