# AI 방문 연결 · 6단계 실행과 검증

음성 원문을 먼저 Room에 저장한 뒤, 연결 가능한 때 원래 날짜·방문 후보로 AI 연결을 제안합니다. AI는 원문이나 기존 사용자 확정 연결을 바꾸지 않습니다. 제안 확정·수정·거절의 전체 화면은 7단계이며 이번에는 연결 설정과 대기/제안/확인 필요/설정 오류만 표시합니다. 전체 UI 다듬기는 후속 P2입니다.

## 앱에서 확인하는 방법

1. [SETUP](../SETUP.md)으로 현재 소스·JDK17/SDK35·전용 Pleos AVD와 기존 데이터/서명을 확인합니다. 주차 P·0 및 신뢰된 차량 상태에서 기본 앱을 엽니다.
2. 총괄이 [백엔드 안내](../../backend/README.md)의 fake 또는 승인된 실제 adapter를 루프백에만 띄우고, 전용 serial에만 reverse를 준비합니다. 기존 forward/reverse나 다른 기기를 덮어쓰지 않습니다. 서버·포트·실제 API·인증 공급은 총괄이 담당합니다.
3. 하루 초안의 **AI 방문 연결 → 연결 설정 열기**로 이동합니다. 개발 서버 주소와 개발 세션 토큰을 정상 UI로 입력합니다. 기본 주소/토큰은 없고 localhost/127.0.0.1만 받습니다. OpenAI 키는 이 화면에 입력하지 않습니다.
4. 저장한 토큰은 AndroidKeystore AES256-GCM과 앱 전용 no-backup 암호문 파일로 보관합니다. 저장된 토큰을 조회하거나 입력창에 복원하지 않고, 저장 여부와 고정 마스킹만 표시합니다. 새 토큰으로 교체할 수 있습니다. D/UNKNOWN/TRANSITION·ON_STOP에서는 입력을 지우고 IME를 닫습니다. 주차 복귀 후 수동으로 다시 엽니다.
5. 원래 원문을 저장한 뒤 WorkManager가 연결 제약 아래 처리합니다. 오프라인 작업은 실제 시도를 소비하지 않습니다. **연결 대기 다시 확인**과 앱 시작은 DB에서 누락된 enqueue를 복구합니다. 모든 날짜의 대기 작업을 읽으며 재접속 날짜로 snapshot을 바꾸지 않습니다.
6. 처리 성공은 방문 카드의 **연결 제안**으로 구분합니다. 애매함/후보 없음/손상 응답/불명확한 결과는 확인 필요이며, 원문은 남습니다. `UNAUTHORIZED`는 세션 토큰 교체, upstream 인증/설정/한도 오류는 서버 설정 확인이 필요합니다. `OUTCOME_UNKNOWN`은 자동 재호출하지 않습니다.

서버 장애는 원문 저장·기존 사진·공유 사용을 막지 않습니다. 실제 녹음·마이크 사용은 기존 [TESTING](../TESTING.md) 절차와 사용자 시험 시점을 따릅니다. 이번 구현자 검사는 합성 입력만 사용했습니다.

## 통신·대기열 계약

- 전송값은 원문·원래 녹음 시각·원래 날짜의 그 시점 이전 최소 방문 ID/이름/체류 시간입니다. 사진·음성 파일·정확한 좌표·주소·전체 DB·OpenAI 키는 보내지 않습니다. 원문 자체에는 개인정보가 포함될 수 있습니다.
- `link-{jobId}` 고유 작업을 KEEP+CONNECTED로 등록하며 Worker input은 jobId 하나입니다. DB job이 기준이며 커밋과 enqueue 사이 종료는 앱 시작 reconciliation으로 복구합니다.
- `AiLinkQueue`가 Room 트랜잭션에서 2분 lease와 매번 새 attemptToken을 발급합니다. HTTP 직전에 최대 5회 시도를 예약합니다. 예약 직후 프로세스가 종료된 좁은 구간도 보수적으로 시도에 포함합니다. demoNow는 lease/backoff 시계에 사용하지 않습니다.
- 요청은 전체 30초, 취소는 HttpURLConnection disconnect로 전달합니다. 리디렉션·HTTP 자동 재전송·SDK 자동 재시도를 하지 않습니다. 30초 기반 지수 backoff와 Retry-After를 모두 지킵니다. Worker가 더 일찍 깨어도 DB 기한 전에는 호출하지 않습니다.
- 안전하게 실패한 연결 거부 및 명시적 retryable 429/5xx만 제한 재시도합니다. read timeout/불명확한 통신은 OUTCOME_UNKNOWN, 인증·한도·설정·admission 거절·사용량 기록 실패는 자동 반복하지 않습니다. 5회 소진도 확인 필요로 남깁니다.
- requestId/noteRevision/userRevision/contextHash echo, 현재 token/lease, 원래 snapshot hash, 현재 사용자 lock/기존 결정, 후보 ID, Unicode code point 범위를 검증합니다. emoji는 UTF-16 경계로 변환하여 표시합니다. 알 수 없는 필드/중복 키/손상 UTF-8·과도한 본문을 거절합니다.
- AI SUGGESTED 링크와 DONE 갱신은 같은 트랜잭션입니다. `Note.text`/`Note.visitId` 및 USER/CONFIRMED/REJECTED 연결은 AI가 쓰지 않습니다. REVIEW/NO_MATCH/계약 실패는 REVIEW_REQUIRED, 늦은 token/revision/hash/lock 결과는 IGNORED_STALE/SUPERSEDED입니다.
- `HttpURLConnection`은 플랫폼 TLS를 유지합니다. debug에만 localhost/127.0.0.1 exact-domain HTTP 예외가 있고 release는 HTTP를 거절합니다. LAN/인터넷 공개 서버나 배포 구성은 이번 범위 밖입니다.

## 자동 검증과 실제 수락의 구분

백엔드는 합성 FastAPI ASGI/SDK MockTransport, Android JVM은 fake connection/실제 JCA 암호문 파일과 실행기, 기기는 테스트 전용 Room 및 WorkManager test driver/Compose를 사용하며, 현재 통합본에는 실제 HTTP 수락 시험도 포함합니다. 실제 OpenAI 호출·계정 접근·한국어 대표 발화 품질/지연/사용량 검증은 총괄의 별도 수락이며 fake 통과로 대체하지 않습니다.

```sh
# 새 환경은 backend/README.md의 공개 의존성을 먼저 준비합니다.
# 외부 venv를 재사용해도 현재 checkout의 app을 import하도록 고정합니다.
PYTHONPATH="$PWD/backend" backend/.venv/bin/python -m pytest backend
backend/.venv/bin/python -m pip check
```

새 환경의 Android 검사는 저장소 루트에서 다음 명령으로 실행합니다. 여러 작업이 빌드를 공유하는 환경에서는 총괄의 공용 잠금으로 직렬화하고, 명령 실행 중 소스를 바꾸지 않습니다.

```sh
./gradlew --no-daemon --max-workers=1 --console=plain \
  testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

현재 동기화 검사는 `app.memoryvault.sync.MemoryLinkQueueTest`16개, `MemoryLinkWorkerTest`11개, `MemoryLinkSetupScreenTest`2개와 신규 `MemoryLinkTransportAcceptanceTest`1개로 **4class/30개**다. 통합 전체는 **135개 메서드·32개 클래스**(기존65/공유33/동기화30/캡처7)이며 [TESTING 3절](../TESTING.md#3-roomcompose-기기-검사)의 전제를 먼저 확인한다. 소스/양APK 해시·실제 기기/user·필터를 기록하고 총괄이 기기를 직렬 조정한다.

## 현재 통합 실제 HTTP 수락 시험

일반 @Test인 `MemoryLinkTransportAcceptanceTest`는 실제 저장된 AndroidKeystore 세션과 정확한 HTTP loopback18761, 로컬 fake 서버 및 전용 serial의 reverse를 요구한다. **필터 없는 전체 실행에도 이 준비가 필요하다.** [영구 fixture 안내](../../scripts/fixtures/README.md)는 새 전용 환경의 backend venv·숨김 입력 세션 파일·정상 주차 설정 UI·서버 시작·reverse·정확한2메서드 필터와 정상 종료를 설명한다. 서버 코드는 [memory_link_transport_server.py](../../scripts/fixtures/memory_link_transport_server.py)에 보존한다.

개발 세션 토큰은 로컬 앱/서버 인증값이며 실제 OpenAI 키가 아니다. 새 개발자는 자기 전용 환경에 같은 토큰의 private 로컬 파일과 정상 주차 UI 세션을 준비한다. fixture는 기존 파일을 읽기만 하고 비밀값을 생성·교체·출력하지 않는다. 기존 검증 기기의 세션 재설정·추가 마이크 사용을 요구하지 않는다. 준비가 빠지면 해당 시험은 실패하며 제품 결함으로 단정하거나 skip으로 바꾸지 않는다.

신규 시험은 자기 UUID 파일 DB에 production `MemoryRepository`로 합성 원문을 저장한다. 논리적 연결 predicate=false에서 WAITING/attempts0/HTTP0을 확인하고, 실제 `HttpURLConnection`과 저장 세션으로 서버의 retryable503을 받는다. DB close/reopen 뒤 같은 원문·job·snapshot을 확인하고 주입 queue 시계를 nextAttemptAt으로 이동한 후 동일 요청의 실HTTP200/SUGGEST, 정확한 emoji 범위, 제안1개/DONE/attempts2와 재열기 보존을 검사한다. 원문·정책과 기존 암호화 세션은 그대로이며 제품DB·음성·사진을 수정하지 않는다.

2026-11-15 총괄의 지정 UI 원문 표시 회귀+신규 HTTP 시험은 단1회 **2/2PASS,2.181초**였다. 서버는 같은 요청 hash에 503(call1)→로컬 fake model1회→200(call2)를 기록했고, 루프백 서버와 이번 reverse를 정상 정리했다. 실제 OpenAI 호출은 없었다. 이 결과는 **논리 offline/주입 queue 시계**이며 실제 OS network 차단·재접속이나 실제30초 경과 backoff 측정이 아니다. 기존 WorkManager 연결 제약 검사와 별도로 판독한다.

앞선 필터 없는 통합134개는132PASS/2FAIL,72.685초였다. UI 표시 영역 가정은 원문 대상 정상 스크롤 후 위 scoped 회차에서 확인했지만, **새135개 전체 GREEN을 뜻하지 않는다.** 캡처7개 중 저장/UI5와 합성 한국어 SDK1의 성공, 무음 SDK1의20초 timeout 실패를 구분한다. 무음 probe는 일반 @Test와 기존 assert/timeout을 유지한다. 전체 suite를 반복하거나 실패를 삭제·skip·완화해 숨기지 않으며 단계/릴리즈에서 선택한 필수 검증과 실패를 그대로 보고한다. NoRoot는 [이슈 #2](https://github.com/chowonbhin/Pleos/issues/2)의 기존 열린 문제이며 이번 미관측을 근본 해결로 확대하지 않는다.

## 과거 6단계 단독 브랜치 검증 이력

아래는 통합과 후속 수정 전의 당시 회차이며 현재135개 목록이나 최신 수락의 대체 근거가 아니다. 해당 단독 브랜치의 JVM212개, backend fake168개/pip check, 기기83개가 통과했다. 당시 Queue12+Worker4+설정UI2+기존65개,27.428초/실패0이었고 Lint오류0/경고31/hint1 및 양APK/DEX SDK logger0/callback1을 확인했다. 인계 직전 Android4-task 명령은 대부분 UP-TO-DATE였으므로212개를 재실행했다고 표현하지 않는다.

그83개 회차에는 실제 세션/reverse/서버가 없었고 설정UI는 fake 저장 경계였다. 당시 실제 계정·12개 합성 발화·정상 Keystore/재실행·기기 전송 수락은 총괄 후속 범위였으며, 현재 기기HTTP의 실세션 경로는 위 통합 scoped 근거로 구분한다. 실제 OpenAI 품질/사용량과 기존 사용자 음성 수락은 [HANDOFF](../HANDOFF.md)의 별도 기록을 따르며 fake 결과로 대체하지 않는다.

## 보관·운영 한계

서버의 process-local cache는 재시작/만료 후 사라집니다. 외부 호출·중복 과금을 exactly-once로 보장하지 않습니다. `store=false`도 제공자의 모든 보관 부재 보장이 아닙니다. 로그에 원문·토큰·전체 요청/응답을 남기지 않습니다. Mac 잠자기/종료나 Android force-stop 중 즉시 백그라운드 처리를 보장하지 않습니다. 모델·effort·Standard 설정과 로컬 admission/usage hook은 [백엔드 안내](../../backend/README.md)를 따릅니다. 가격·예산·실제 사용 금액·비밀값은 공유 자료에 넣지 않습니다.
