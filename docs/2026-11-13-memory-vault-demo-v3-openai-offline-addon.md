# 기억 저장소 데모 v3 — 오프라인 저장·OpenAI 방문 연결 추가 계획

> For agentic workers: 구현 시 승인된 실행 방식에 따라 superpowers:subagent-driven-development 또는 superpowers:executing-plans를 적용한다. 이 문서는 기존 v2를 수정하지 않는 추가 설계·계획이며, 문서 작성과 제품 구현을 구분한다.

**작성일:** 2026-11-13, Asia/Seoul.

**기준 문서:** `2026-11-13-memory-vault-demo-v2.md`(Downloads, SHA-256 `ab4a8a254d8a369ef2466e6772dd3b12a6ac29fd00783f2d0b7f1da9be1f7ee6`).

**Goal:** 오프라인에서도 음성 인식 결과를 먼저 저장하고, 연결이 가능해지면 OpenAI가 발화 내용과 저장된 방문 기록을 대조해 관련 방문을 제안하도록 한다.

**Architecture:** Room을 앱 데이터의 기준으로 유지한다. 원문 저장과 AI 작업 대기열 등록을 같은 트랜잭션으로 처리하고, WorkManager가 연결 후 작업을 수행한다. OpenAI 호출은 키를 보유한 백엔드를 거치며, 앱은 결과를 검증하고 사용자 수정과 충돌하지 않을 때만 AI 연결 제안으로 저장한다.

**Tech Stack:** 기존 Kotlin·Compose·Room·Pleos ON_DEVICE STT + Android WorkManager + HTTP 클라이언트 + Python/FastAPI 백엔드 + OpenAI Responses API/Structured Outputs. 새 라이브러리는 구현 시작 시 호환되는 안정 버전을 고정한다.

**상태:** 추가 계획 작성 완료, 제품 코드·에뮬레이터·백엔드·API 호출은 미실행. 기존 PRD·v1·v2·검토 문서는 변경하지 않았다. 최초 설계·실행 계획 승인 절차는 유지한다.

## 1. 사용자가 확정한 방향과 적용 범위

사용자는 OpenAI 기반 방문 연결을 별도로 반영하고, 오프라인에서 먼저 저장했다가 연결 시 API를 사용하도록 요청했다. 따라서 이제 '완전 오프라인'이 아니라 **오프라인 우선 저장 + 연결 시 AI 보완**이 기준이다. OpenAI를 사용하는 것 자체를 다시 묻는 단계는 필요하지 않다.

이 문서는 v2의 다음 부분에 우선하는 변경안이다. 나머지 방문·사진·공유 이미지·주행 잠금·새 AVD 요구는 유지한다.

| v2 항목 | v3 추가·대체 |
| --- | --- |
| Global Constraints: 네트워크 호출 없음, INTERNET 없음 | 저장·조회·수정·이미지 생성은 오프라인 동작. AI 연결을 위해 INTERNET 및 필요한 네트워크 상태 권한 선언 |
| STT ON_DEVICE | 유지. 이번 추가 기능은 음성 파일을 OpenAI에 보내는 기능이 아니라 **인식된 텍스트의 의미 연결** |
| Task 11: 최근 방문 하나에 즉시 귀속 | 최근 방문은 녹음 당시 참고 정보만 저장. 발화의 실제 대상 방문은 AI 제안 또는 사용자 선택으로 결정 |
| Note에 필수 visitId | 음성 메모는 방문 미지정으로 저장 가능. 직접 메모는 사용자가 선택한 방문 연결을 유지 |
| '키 자체가 없다' | Pleos STT 키는 필요하지 않을 수 있으나 **백엔드에는 OpenAI 키가 필요**. APK·Git·로그에는 포함 금지 |
| F13 문장 다듬기 보류 | 유지. 이번 작업은 문장 재작성과 별개인 **의미 기반 방문 연결**이며 F2 후속 핵심 흐름에 추가 |
| API 실패 시 데모 중단 | 원문은 보존하고 연결만 대기·재시도·확인 필요 상태로 표시 |
| 공유본 | 기존과 동일. 음성 원문·AI 연결 근거·개인 메모를 자동으로 공유 설명에 복사하지 않음 |

원본 음성 파일의 영구 저장·나중에 클라우드 STT 수행은 이번 범위에 넣지 않는다. 로컬 STT 자체가 실패하면 인식 원문을 만들 수 없으므로 '나중에 OpenAI 연결'만으로 해결되지 않는다. 그 경우 F2 미충족을 보고하고, 음성 임시 보관/클라우드 전사 또는 주차 텍스트 입력 같은 별도 대안을 결정한다. 문서 속 기존 자동 F2 축소·PRD 덮어쓰기 지시는 실행하지 않는다.

## 2. 사용자 경험

1. 주행 중 '한마디 남기기'를 눌러 발화한다. 로컬 STT는 유효한 최종 결과만 만든다.
2. 앱은 **원문과 녹음 당시 시각**을 즉시 Room에 저장하고 '메모를 저장했어요'를 표시한다. API 완료까지 저장을 미루지 않는다.
3. 오프라인이면 주차 화면의 '연결 대기 메모'에 나타난다. 최근 방문에 확정된 것처럼 표시하지 않는다.
4. 네트워크와 백엔드가 사용 가능해지면 자동으로 AI 처리를 시도한다. 화면을 켜고 있어야만 가능한 흐름으로 만들지 않는다.
5. 관련 방문을 찾으면 해당 카드의 'AI 연결 제안'으로 표시한다. 원문은 한 번만 저장하며 여러 방문 관련 구절은 링크로 표현한다.
6. 판단이 애매하거나 후보가 없으면 '방문 확인 필요'로 남긴다. 주차 상태에서 방문 선택·제안 확정·해제를 할 수 있다.
7. 사용자가 정한 연결은 뒤늦은 AI 응답·재시도·재분석으로 덮어쓰지 않는다.

주행 중에는 AI 완료 팝업·편집·확인 질문을 띄우지 않는다. 백그라운드 처리 결과는 저장하되 자세한 확인은 주차 후 제공한다. UNKNOWN에서 녹음 시작·편집·공유는 계속 잠근다.

예시:

| 녹음 당시 상황·발화 | 기대 동작 |
| --- | --- |
| 카페 방문 중 '아까 칼국수집 국물이 좋았어' | 식당 연결 제안. 녹음 당시 최근 방문인 카페로 강제 귀속하지 않음 |
| 해변을 떠나며 '애들이 바다에서 한참 놀았네' | 해당 날짜 해변 방문을 근거로 연결 제안 |
| '거기 정말 좋았어' | 후보를 결정할 근거가 부족하면 확인 필요 |
| '식당은 좋았는데 카페는 시끄러웠어' | 원문 1건에 식당·카페 관련 구절을 각각 링크 |
| 다른 날짜에 재접속 | 재접속 날짜가 아니라 원래 녹음 날짜·맥락을 사용 |

## 3. 로컬 데이터와 트랜잭션

v2의 초기 DB를 만들 때 아래 구조를 함께 설계한다. 이미 사용자 데이터가 있는 DB가 발견되면 파괴적 재생성 대신 migration 계획을 먼저 세운다.

| 모델 | 필드와 의미 |
| --- | --- |
| `Note` | 기존 원문·kind·createdAt 유지. `visitId`는 nullable로 변경하며 직접 선택/사용자 확정 단일 연결의 호환용 값. `captureVisitId?`, `capturedAt`, `sourceDate`, `revision`, `voiceSessionId?` 추가 |
| `NoteVisitLink` | `noteId`, `visitId`, 원문 구절 `startCp/endCp`, `origin=AI/USER`, `status=SUGGESTED/CONFIRMED/REJECTED`, `requestId?`. 여러 방문 연결 지원 |
| `NoteLinkPolicy` | `noteId`, `userRevision`, `userLocked`. 사용자 확인·수정·거절 시 증가/잠금. 거절한 링크를 AI가 복원하지 못하게 함 |
| `AiLinkJob` | `jobId`, `noteId`, `noteRevision`, `userRevision`, `contextHash`, `promptVersion`, `state`, `attempts`, `nextAttemptAt`, `leaseUntil`, `attemptToken?`, `lastErrorCode?` |
| `AiLinkRequestSnapshot` | `jobId`, 전송할 후보 방문 스냅샷. Room에 보관하며 같은 요청을 재시도할 때 후보를 몰래 변경하지 않음 |

AI의 제안만으로 기존 `Note.visitId`를 덮어쓰지 않는다. 카드 조회는 직접 메모와 NoteVisitLink를 합쳐 구성하되 같은 noteId·visitId를 중복 표시하지 않는다. 과거 v2 음성 메모의 visitId는 자동으로 사용자 확정으로 승격하지 않는다.

한 트랜잭션에서 `Note` 원문 저장 + 기본 정책 + AI job/snapshot을 생성한다. 저장이 성공하면 API 상태와 관계없이 로컬 저장 성공이다. 같은 voiceSessionId를 UNIQUE로 제한해 중복 STT final이 메모를 두 번 만들지 않도록 한다.

WorkManager 등록은 Room 트랜잭션과 하나의 원자 작업이 아니다. 커밋 후 enqueue하며, 그 사이 앱이 종료되어 job만 남은 경우 앱 시작 때 `reconcilePendingJobs()`가 누락된 스케줄을 복구한다. DB 대기열이 기준이며 Worker input에는 jobId만 넣는다.

시연용 방문·capturedAt에는 `DEMO_ZONE`/시드 시각을 사용해도 되지만, 네트워크 재시도·lease·타임아웃은 실제 시스템 시간/단조 시간으로 관리한다. 고정된 demoNow를 재시도 시계로 사용하지 않는다.

## 4. 연결 후 실행과 실패 복구

WorkManager의 네트워크 연결 제약과 고유 작업을 사용한다. Android의 스케줄 제약 때문에 **연결되는 즉시 실행되거나 몇 초 안에 완료된다고 보장하지 않는다**. 인터넷 연결 상태만으로 OpenAI·백엔드 접근 성공도 보장되지 않는다. [S1, S2]

고유 작업 이름은 `link-{jobId}`이며 같은 작업은 KEEP으로 등록한다. Worker는 DB에서 lease를 원자적으로 획득한 경우에만 실행한다. 앱 시작·새 메모 저장·주차 화면의 '연결 다시 시도'에서 같은 스케줄러를 사용한다.

| 상태/결과 | 처리 |
| --- | --- |
| WAITING | 오프라인·예약 대기. 원문은 읽고 수정·수동 연결 가능 |
| RUNNING | lease를 보유한 요청 하나 실행. 중복 Worker는 호출하지 않음 |
| RETRY_WAIT | 네트워크 오류·일시적 429·5xx. 30초 기반 지수 backoff, Retry-After가 있으면 그보다 일찍 재시도하지 않음 |
| REVIEW_REQUIRED | 애매함·후보 없음·모델 거절·의미 검증 실패. 무한 재호출하지 않고 사용자 확인 |
| DONE | 검증된 링크 제안을 원자 적용한 상태. 사용자 확정과는 별도 |
| BLOCKED | 인증 실패·한도/결제 문제·잘못된 모델/요청 등 설정 수정 필요. 자동 반복 금지 |
| SUPERSEDED | 사용자 수정·메모 revision·후보 맥락 변경으로 오래된 요청. 결과 적용 금지 |

제안 기본값: 네트워크 요청 전체 제한 30초, lease 2분, 자동 실제 시도 최대 5회. 도구 자체의 자동 재시도와 Worker 재시도가 겹쳐 폭증하지 않게 백엔드/SDK의 재시도 책임을 하나로 정한다. 5회 소진 시 수동 재시도 가능한 상태로 둔다. 네트워크 제약 때문에 아직 실행하지 않은 대기는 시도 횟수에 넣지 않는다.

작업 중 네트워크 상실/프로세스 종료로 RUNNING이 남으면 lease 만료 후 회수한다. lease 획득마다 새 attemptToken을 발급하고 결과 적용 시 현재 token과 비교한다. 취소 신호는 HTTP 요청에도 전달하고, lease가 만료되거나 교체된 Worker의 늦은 결과는 무시한다. OS에서 앱을 강제 중지한 뒤에는 재실행 전 백그라운드 처리를 보장하지 않는다. Mac 백엔드가 잠자기·종료된 경우도 대기하며, 항상 켜진 운영 서버처럼 표현하지 않는다.

## 5. OpenAI 요청과 결과 검증

음성 원본 파일·사진·주소·정확한 좌표·전체 DB는 보내지 않는다. 이번 데모는 **메모 원문 + 원래 녹음 날짜/시각 + 그 날짜의 방문 후보 ID·장소명·도착/출발 시각**만 보낸다. 메모 문장 자체에 개인정보가 포함될 수 있다는 점은 주차 상태의 기능 안내에 설명한다.

후보는 원래 날짜를 기준으로 준비하고 시드의 미래 방문을 실제 과거 방문처럼 제시하지 않는다. `arrivedAt <= capturedAt`인 방문을 기본으로 한다. 해당 후보에 없는 장소·과거 다른 날짜를 말하면 범위를 넓혀 전송하지 않고 확인 필요로 남긴다. 여러 날짜 검색은 별도 확장이다.

백엔드 `POST /v1/memory-links` 요청 계약:

```json
{
  "requestId": "opaque-job-uuid",
  "noteRevision": 1,
  "userRevision": 0,
  "contextHash": "snapshot-digest",
  "capturedAt": "2026-11-08T15:30:00+09:00",
  "text": "아까 칼국수집 국물이 좋았어",
  "candidates": [
    {"id":"v1","placeName":"대부도 칼국수집","arrivedAt":"2026-11-08T12:00:00+09:00","leftAt":"2026-11-08T13:10:00+09:00"}
  ]
}
```

예시는 후보 일부만 표시한 것이다. 실제 요청에는 해당 시점의 유효 후보를 모두 포함한다. 호출 모델은 백엔드의 `OPENAI_LINK_MODEL`로 고정한다. 계정에서 실제 사용할 수 있고 Structured Outputs를 지원하는 모델을 대표 발화 평가 후 선택하며, 실패 시 다른 모델로 몰래 변경하지 않는다.

Responses API에 `store:false`와 엄격한 JSON schema를 사용한다. 대화 이력·Conversations·파일 업로드·도구 호출·웹 검색은 필요하지 않다. 원문과 방문 이름에 들어 있는 명령형 문장을 데이터로만 취급한다. 모델은 **주어진 후보 중 선택하거나 연결을 보류**하며, 없는 방문·동행자·감정·사실을 생성하지 않는다. [S3, S4]

모델 출력은 `decision=SUGGEST/REVIEW/NO_MATCH`, `links=[{visitId,evidenceQuote}]`만 허용하고 추가 필드는 금지한다. 백엔드는 모델이 반환한 evidenceQuote가 원문에 실제 존재하는지 확인하고 Unicode code point 기준 `[startCp,endCp)`로 변환한다. 동일 구절이 여러 번 나타나 위치가 애매하면 자동 확정하지 않는다. Android는 UTF-16 인덱스로 변환하는 함수를 두고 emoji 포함 테스트를 작성한다.

응답에는 서버가 원래 요청의 requestId/revision/contextHash를 그대로 붙인다. 모델이 이 식별자를 결정하지 않는다. 앱은 다음을 검증한 뒤 한 트랜잭션으로 결과를 적용한다.

- 반환 visitId는 보낸 후보 집합에 포함되는가?
- 근거 구절과 범위가 저장된 원문과 정확히 일치하는가?
- noteRevision/userRevision/contextHash가 현재 처리 대상과 같은가?
- 사용자가 이미 연결을 확정·수정·거절해 잠갔는가?
- 중복 링크·허용되지 않은 필드·불완전/거절 응답이 없는가?

형식 검증은 연결 판단의 정확성을 보장하지 않는다. 유효 결과도 AI 제안으로 구분하고 주차 후 수정할 수 있게 한다. 모델이 스스로 출력한 confidence 숫자 하나로 확정하지 않는다. [S4]

## 6. 백엔드·키·중복 호출·보관

OpenAI 비밀키는 서버 환경 변수 또는 비밀 저장소에 둔다. APK, BuildConfig, resources, 샘플 요청, Git, 로그에 넣지 않는다. 모델 결과의 링크만 앱에 반환한다. [S5]

첫 데모는 Mac의 루프백 주소에만 바인딩하는 개발용 백엔드로 구성할 수 있다. 새 Pleos AVD serial에만 `adb reverse`를 연결해 사용한다. 인터넷에 공개하거나 LAN 전체에 바인딩하지 않는다. 개발용 연결이 HTTP라면 localhost에 한정된 debug network security 설정만 두고 배포 빌드에서는 금지한다. OpenAI와의 통신은 HTTPS다. 실제 배포 시에는 인증·권한 검증이 있는 HTTPS 백엔드가 필요하며 별도 배포 범위다.

로컬 backend도 개발 세션 인증을 적용한다. 토큰은 실행 시 생성·별도 전달하며 APK에 하드코딩하지 않는다. 백엔드는 인증 실패·과도한 본문·허용량 초과 요청을 API 호출 전에 거절한다. 데모 기본 상한 제안은 원문 4,000자, 후보 20개, 응답 링크 10개이며 초과 데이터는 조용히 잘라 보내지 않고 검토 대상으로 남긴다.

requestId는 멱등 처리 키다. 백엔드는 요청 본문의 hash와 연결해 중복 동시 요청을 합치고 완료된 동일 요청에는 기존 결과를 반환한다. 같은 키로 다른 본문이 오면 409를 반환한다. 캐시에 원문·후보 장소명은 저장하지 않고 ID·해시·검증된 링크 인덱스·상태만 보관한다. 데모 로컬 캐시 보관 상한은 24시간으로 둔다.

**외부 모델 호출을 절대 한 번만 실행한다거나 중복 과금이 절대 없다고 보장하지 않는다.** API 처리 후 응답 수신/백엔드 캐시 저장 전에 연결이 끊기면 결과가 불명확할 수 있다. 명확한 완료 캐시는 재사용하고, 불명확한 요청은 기록해 재시도 횟수·비용을 제한한다. 앱의 메모·링크 반영은 DB 고유키와 revision 검사로 중복을 방지한다.

로그에는 requestId·오류 분류·지연·시도 횟수만 남기고 원문·사진 URI·키·Authorization·전체 요청/응답은 제외한다. `store:false`는 Responses의 응답 저장을 줄이는 설정이지, 모든 서버 보관을 없애는 보증은 아니다. OpenAI의 실제 데이터 보관 조건을 기능 안내에 정확히 반영한다. [S6]

## 7. v2 작업에 결합할 순차 개발 작업

새 에뮬레이터 보존·minSdk 29·사진/공유 기능은 v2를 유지한다. 아래 작업은 추가 검토 가능한 단위로 순서대로 수행하고, 적용 전 최초 계획 승인을 받는다. 이후 일반 단계마다 재승인하지 않는다.

### AI-1: 연결 미지정 메모·대기열을 먼저 저장

**삽입 위치:** v2 Task 3의 DB 설계 시 계약 반영, Task 7의 메모 저장 후 완성. Task 11은 이 저장 함수를 사용한다.

**소유 파일:** `app/src/main/java/app/memoryvault/data/{Entities,Daos,AppDatabase,MemoryRepository}.kt`, 신규 `data/AiLinkQueue.kt`, `domain/MemoryLink.kt`; androidTest `data/AiLinkQueueTest.kt`.

**계약:** `saveVoiceMemo(sessionId: String,text: String,capturedAt: Long): Long`은 suspend 함수이며 저장된 noteId를 반환한다. 공백 입력은 저장하지 않고 IllegalArgumentException으로 거절하며, DB 실패는 호출자에게 전달한다. 유효 발화는 방문이 없어도 저장한다. `enqueueLinkJob(noteId: Long): String`은 원문 revision과 후보 snapshot을 묶은 jobId를 반환한다.

- [ ] 실패 테스트: offline에서 Note 1개+job 1개; DB 실패 시 둘 다 0개; 동일 sessionId 재호출은 동일 noteId와 job; 방문 0개여도 메모 보존; 원문 불변.
- [ ] 새 AVD serial의 해당 Room 테스트로 실패를 확인한다.
- [ ] nullable 방문·link 테이블·트랜잭션·중복키·후보 snapshot을 구현한다.
- [ ] 재시작 후 대기 메모를 읽는 instrumented test와 전체 JVM·lint·빌드를 실행한다.
- [ ] 소유 변경·비밀정보 검사 후 local commit. 결과물은 인터넷 없이 메모가 남는 실행 앱이다.

### AI-2: OpenAI 연결 서비스와 응답 검증

**의존성:** AI-1의 요청/응답 계약. 제품 코드 외 백엔드 파일의 단일 소유자를 지정한다.

**소유 파일:** 신규 `backend/app/{main,schemas,link_service,openai_adapter,idempotency_store}.py`, `backend/tests/test_link_service.py`, `backend/pyproject.toml`, `backend/README.md`. 키나 실제 세션 토큰은 파일로 작성하지 않는다.

**계약:** `POST /v1/memory-links`; `LinkService.link(request)`는 후보 검증을 통과한 응답만 반환한다. 인증·본문 제한·멱등 검사는 모델 호출 전 수행한다. 서버용 `OPENAI_API_KEY`와 `OPENAI_LINK_MODEL`은 실행 환경에서 읽는다.

- [ ] fake 모델 실패 테스트: 최근 방문과 다른 명시 장소, 복수 방문, 모호한 발화, 후보 밖 ID, 원문에 없는 근거, 원문 내 명령문, 동일 requestId 중복/본문 변경, 인증 실패.
- [ ] `python -m pytest backend/tests/test_link_service.py`의 실패 확인 후 최소 백엔드·Responses 어댑터·schema 검증을 구현한다.
- [ ] 전체 backend 테스트를 실행한다. 실제 API 검증은 올바른 로컬 키·모델·사용 한도를 설정한 뒤 합성 발화로 수행하며 키를 채팅에 붙여넣도록 요청하지 않는다.
- [ ] 최소 12개 대표 발화에서 결과·불확실성 보류·지연·사용량을 확인한다. 실제 호출 없이 fake 결과만으로 OpenAI 연동 완료를 선언하지 않는다.
- [ ] local commit. 결과물은 제한된 개발용 서버와 합성 입력에 대한 실제 연결 응답이다. 원격 배포는 하지 않는다.

### AI-3: 연결 시 재시도·늦은 응답 충돌 방지

**의존성:** AI-1/AI-2. v2 Task 11의 STT 연결 전에 fake HTTP로 개발할 수 있다.

**소유 파일:** 신규 `app/src/main/java/app/memoryvault/sync/{MemoryLinkWorker,MemoryLinkScheduler}.kt`, `platform/MemoryLinkApi.kt`, `data/AiLinkQueue.kt`, Gradle 의존성·manifest; androidTest `sync/MemoryLinkWorkerTest.kt`.

**계약:** `schedule(jobId: String)`, `reconcilePendingJobs()`; `applyResult(jobId: String,result: LinkResponse): ApplyOutcome`은 suspend 함수이며 `APPLIED/IGNORED_STALE/REVIEW_REQUIRED`를 반환한다. revision·attemptToken 비교, 링크 저장, job 완료는 같은 DB 트랜잭션이다.

- [ ] 실패 테스트: 네트워크 없으면 API0회; 연결 후 실행; 네트워크 오류·일시적 429/5xx는 제한 재시도; 401/한도 실패는 중단; 중복 Worker·lease 회수; 사용자 수정 뒤 늦은 응답 무시; 날짜가 바뀐 재접속에서도 원래 후보 사용.
- [ ] WorkManager 테스트 도구로 실패를 확인하고 unique work·제약·backoff·revision 비교를 구현한다.
- [ ] 새 AVD에서 실제 네트워크 차단→저장→앱 재시작→연결 복구를 검증한다. 기기 네트워크는 켜져 있지만 Mac 백엔드만 꺼진 경우도 검증한다. 호스트 전체 네트워크를 임의로 끊지 않는다.
- [ ] enqueue 전 프로세스 종료 구간은 앱 재시작 reconciliation으로 회복되는지 검증한다. 강제 중지 상태에서는 즉시 실행을 약속하지 않는다.
- [ ] 전체 JVM·lint·instrumentation·빌드 후 local commit. 결과물은 재접속 뒤 대기 메모가 AI 제안으로 바뀌는 실행 앱이다.

### AI-4: 주차 후 연결 확인·최종 시연

**의존성:** AI-3와 v2 Task 10/11. v2 Task 12 전에 수행한다.

**소유 파일:** `ui/{DayDraftScreen,DayDraftViewModel,DrivingScreen}.kt`, 신규 `ui/MemoryLinkReviewScreen.kt`, `data/MemoryRepository.kt`; androidTest `ui/MemoryLinkFlowTest.kt`, 신규 `docs/verification/openai-offline-linking.md`.

**계약:** `confirmLinks(noteId: Long,visitIds: Set<String>)`, `rejectSuggestions(noteId: Long)`, `retryLinking(noteId: Long)`은 사용자 revision/lock과 job 상태를 일관되게 갱신한다. 자동 재분석은 잠긴 메모를 변경하지 않는다. 사용자가 명시적으로 재분석할 때만 새 revision으로 잠금을 해제한다.

- [ ] 실패 테스트: 대기 메모 별도 표시, AI 제안/사용자 확정 구분, 중복 카드 표시 방지, 사용자 수정 보존, emoji 구절 범위, 주행 중 확인 화면 차단, 공유 이미지에 메모·근거 미포함.
- [ ] UI와 저장 액션을 구현하고 전체 검증한다.
- [ ] 실제 시연: 오프라인에서 '아까 칼국수집 국물이 좋았어' 저장 → 카페로 잘못 확정되지 않음 → 재접속 → 식당 연결 제안 → 주차 후 확인 → 카페를 제외한 공유 PNG에도 메모 원문은 없음.
- [ ] 추가 실패 시연: API 응답 전 수동 연결 → 늦은 결과가 사용자 연결을 덮어쓰지 않음. API 장애에도 원문·기존 사진·공유는 사용 가능.
- [ ] 매 push 전 소스·백엔드·전송 이력·APK·로그·스크린샷의 비밀정보를 검사하고 키 값은 출력하지 않는다. 새 기능에서는 INTERNET 존재가 정상이며 키 없음의 증거로 삼지 않는다.
- [ ] local commit, 실제 APK·실제 API 증거·시연 영상과 미검증 범위를 함께 제공한다. 공개 push·배포·메일 발송은 별도 명시 승인 범위에서만 실행한다.

## 8. 최소 완료 기준과 일정 영향

- 오프라인에서 유효 음성 원문 저장 성공. API 오류가 로컬 저장 실패로 바뀌지 않는다.
- 연결 후 원래 날짜·맥락으로 요청하며, 최근 방문과 다른 명시 장소를 정확히 제안한다.
- 애매한 발화·목록 밖 장소는 억지로 연결하지 않는다.
- 원문 하나의 복수 장소를 링크로 처리하며 원문을 복제·재작성하지 않는다.
- 앱 재시작·연결 끊김·중복 시도·사용자 수정 후 응답에도 데이터가 보존된다.
- 실제 OpenAI 호출로 최소 대표 발화를 검증한다. 가짜 응답 시연은 실제 AI 시연과 구분한다.
- 기존 주행 잠금·사진·공유 제외 요구와 새 AVD 보존이 유지된다.
- OpenAI 키는 APK·Git·로그에 없고 backend에서만 사용된다.

추가 작업은 단순 SDK 교체가 아니다. 백엔드·지연 작업·링크 모델·UI/검증이 추가되므로 기존 일정에 **대략 6~10시간의 추가 구현·통합 여유**가 필요하다는 계획 추정이다. 기존 P1 배지 확장·사진 옮기기·문장 다듬기·3D보다 이 기능을 우선한다. 최종 안전·보안 검증 시간은 줄이지 않는다. 실제 첫 API·STT 시험 후 일정을 재산정한다.

## 9. 근거와 검토 범위

공식 문서 확인일: 2026-11-13. 아래 공식 API·Android 동작을 토대로 설계했으며, 본 문서 작성 시 실제 앱·API 테스트는 수행하지 않았다. 추가 계획은 자체 계약·실패 시나리오 검토를 거쳤으며 독립 subagent 검토는 미실시다.

- [S1 Android 오프라인 우선 설계](https://developer.android.com/topic/architecture/data-layer/offline-first)
- [S2 WorkManager 제약·재시도](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)
- [S3 Responses API와 store:false](https://developers.openai.com/api/docs/guides/migrate-to-responses)
- [S4 Structured Outputs와 의미 오류의 한계](https://developers.openai.com/api/docs/guides/structured-outputs?api-mode=responses)
- [S5 OpenAI API 키 관리](https://developers.openai.com/api/docs/guides/api-keys)
- [S6 OpenAI 데이터 보관](https://developers.openai.com/api/docs/guides/your-data)

이 문서는 사용자 수정본 옆에 새로운 파일로 제공한다. 현재 채팅의 이전 프로젝트 경로가 열리지 않아 프로젝트 파일에 반영했다고 주장하지 않는다. 구현 시작 시 실제 작업 폴더·Git 상태와 승인된 실행 방식을 확인해야 한다.
