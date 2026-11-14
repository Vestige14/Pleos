# Stage7 기억 연결 검증 — 2026-11-15

최신 수락: UI/AI 및 실제 현재·최근 목적지 저장을 확인했으며 내비 위치 미갱신·실제 도착 검증 공백은 이슈 #4로 이월했다. 7단계 기능 완료와 전체 기기 GREEN은 구분한다. [root 직접 후속 검증](stage7-root-followup.md)과 HANDOFF의 게시 결과를 우선하며 아래는 V 시점의 원래 결과다.

7단계 신규 핵심 기기 검사13개는 통과했다. 관련 통합 기기검사는 **146개 중142PASS/4FAIL,59.577초**다. 새 전체 GREEN 또는 단계 최종 수락으로 표시하지 않는다. 원인 미확정의 기존 UI4개 실패와 실제 내비 제공자 지원 판정 미확정, 실모델 의미 연결의 REVIEW 결과를 총괄의 수락 판단에 남긴다.

검증자는 V 한 명이며 구현자/root의 반복 검증은 없었다. 최신 설계·공유 계약 및 사용자 정정을 따랐다. Stage8/main 병합/push/Release는 실행하지 않았다.

## 소스와 실행 산출물

|대상|식별|
|---|---|
|브랜치/HEAD|`feature/07-memory-links` / `bb6f6f743652b10c556a3316e8f6af79e78846f2` + root의 미커밋 조립/C fix1·fix2/V 시험|
|main|`259cb9abb74ebd3659190de52db42be94ab56428`, V가 변경하지 않음|
|제품 APK|`app/build/outputs/apk/debug/app-debug.apk`|
|제품 SHA-256|`0c068687bf5651c30d70c5f5385a36375f18337712d698544827490ac3efcb8b`|
|시험 APK|`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`|
|시험 SHA-256|`b426e73110bfca7db2f53e8c78157e9abfea9ff24f394b0ce0e78522536e12b3`|
|최종 소스·시험·schema·fixture233경로 manifest SHA-256|`1bc19e54e7b2683b94987bccb9eecfdaf37e7e5f81d609b925f50f586c5b70aa`|
|기기|Pleos_MemoryVault_API34 / Pleos Connect v2.1.0 / Android14 ARM64 / 현재user10|
|ADB|서버5038, 실제transport `127.0.0.1:5581`, console alias `emulator-5580`|
|환경|Temurin17.0.21, SDK35/Build Tools35.0.0, 전용Python3.11.5 + 현재backend lock|

기존 설치와 최종 양APK의 서명을 직접 대조한 뒤 `install -r --user 10`으로 업데이트했다. 마지막 설치본의 SHA도 위 제품 APK와 일치했다. clear-data/uninstall/reboot/OS권한·기본앱·경로 변경은 하지 않았다. 원음·사용자 사진·실제 메모 원문을 보고서나 서버 시험 입력에 복사하지 않았다. 실제 사용자 원음의 재생/바이트 재검수는 이번에 반복하지 않았다.

## 실행한 검사와 범위

|검사|결과/근거|
|---|---|
|신규 backend 핵심|22PASS: 사진 맥락 격리/metadata/JPEG 경계, 녹음시계 날짜/10월9일 반례, NOTE 자신의 원문·시각, NAV provenance, 후보0/모호함/정정충돌0호출, strict ID/quote/codepoint, replay/409, shared transport/admission/usage/UNKNOWN|
|전체 backend|190PASS, pip check 이상 없음. 실제 OpenAI를 쓰지 않는 합성 검사|
|최초 전체 JVM|323개 중319PASS/4FAIL. 새 HTTP 시험의 잘못된 endpoint fixture를 바로잡은 뒤 scoped4 중3PASS/1 제품 오류; C fix2 뒤 해당4PASS. 영향 없는319개의 증거는 재사용하며 새 전체323 단일 GREEN 실행으로 부풀리지 않음|
|초기 Kotlin 결함 수정|C fix1 뒤 날짜·Unicode·PARKED/foreground/generation4 + 비동기 내비 fake5 =9PASS|
|Lint/양 APK|최종 빌드 성공, 오류0/경고36. 기존 네이티브 심볼/JNA 정렬 및 버전 권고를 숨기지 않음|
|제품 DEX|DEX3, STT SDK class108, vendor logger invoke0, SDK caller Android log invoke0, 결과 callback invoke1. 실제 발화 성공의 대체 증거 아님|
|관련 기기 통합|146unique/142PASS/4FAIL,59.577초. 기존 무음 probe와 이미 수락한 합성 한국어 SDK probe2개를 재실행하지 않은 명시 범위|
|신규 기기13개|13PASS: `Stage7PersistenceTest`9 + `Stage7PhotoSafetyTest`1 + `Stage7ReviewScreenTest`2 + `Stage7TransportFlowTest`1|
|실모델 최소 수락|합성 이미지 입력200/설명 반환, 후속 의미 연결200/REVIEW/links0. 동일 요청 replay와 후보0의 추가 inference0. 정확히2 inference, 승인 모델/low/default, actual usage default 확인. fallback/반복 없음|

실제 호출의 비용·가격·예약/사용량 금액·자격증명과 원시 응답은 로컬 전용이다. 기존 단일 누적 장부와 잠금을 사용했고, 과거 불명확 호출의 예약 및 촬영 잔여를 보존했다. 사진의 모델별 patch 계수를 추측하지 않고 공식 모델 context 상한으로 보수 예약한 뒤 usage를 정산했다. [공식 모델](https://developers.openai.com/api/docs/models/gpt-6.1-terra)·[이미지 입력 안내](https://developers.openai.com/api/docs/guides/images-vision)의 지원 설명과 실제 synthetic image 입력 성공을 구분한다.

## 핵심 수락 매트릭스

|요구|직접 확인한 증거|한계|
|---|---|---|
|사진 단독 분석|transport 입력은 고정 지시+이미지 하나. STT/파일명/날짜/좌표 sentinel 제외. 합성3200×1600 JPEG→방향90도 반영/긴변≤1024/≤524288bytes/hash/EXIF·GPS 제거|sanitizer의 모든 방향8종/모든 native decoder 실패를 실기 검증한 것은 아님|
|설명 cache/재시작|Room close/reopen 뒤 같은hash/model/version의 설명 재사용; 불명확 receipt 반복 호출0|backend 재시작 이후 exactly-once 보장 아님|
|다른 날짜/독립 NOTE|10월9일 사진과11월1일 오늘 발화 제외·0모델호출. 긴 NOTE의 말미 날짜와 자신의 기록시각 유지|무제한 자연어 시간 표현 정확성을 뜻하지 않음|
|내비 시간/위치|fake async getter1쌍, 실제 callback clock, 최근 undated, arrival eventAt/좌표null, stale/cancel/close/retention. Android 후보 NAV_ARRIVED 관측일/null 매핑 통과|실제 제공자 응답 상태/빈목록/unsupported 판정과 실제 도착callback은 미확정. 시험의 상태 marker가 보존 로그에서 확인되지 않아 AVAILABLE/UNAVAILABLE 또는 row수를 단정하지 않음|
|사용자 날짜 정정|정정값 보존/REVIEW_REQUIRED/분석job0/같은 정정 반복 차단/수동 연결 가능/원문불변|실API 자동 날짜 override 기능 아님|
|migration/유료 경로|Room1→2→3 및2→3 실제 schema validation, 원문/사진확정/정책/legacy job 보존. 새 production 등록 callback의 v1 job0, v1 scheduler가v2 claim 불가|새 note→전체 Application 준비 lifecycle의 모든 crash 지점 조합을 실기 재현한 것은 아님|
|사용자 결정/stale|제안 확정·수동 수정·해제·재분석·DB재열기, late 응답 거절, confirmed 재분석 잠금, 거절 대상 자동 부활 방지|‘이번 제안 거절’ 버튼의 독립 end-to-end 경로는 코드검토이며 이13개에서 별도 클릭·재시작하지 않음|
|안전/입구|foreground/세대/DRIVING 잠금 JVM, private text 숨김 Compose, 실제 PARKED 상태의 격리 정상 확정 UI, 정상 Main→기억연결→잠긴 안내 확인|실제 OS D/UNKNOWN 전환·화면 회전/TalkBack/최대글꼴/테마 조합을 새로 반복하지 않음|
|실제 Android HTTP|기존 암호화 세션 read-only, synthetic photo→production v2 HTTP→서버 인증/검증/fake모델→제안→production Compose 확정→DB재열기. 기존 v1의503→재열기→200도 이번 suite에서 통과|fake모델 수락이며 실모델의 제안 성공으로 대체하지 않음. 실제OS 네트워크 차단/backoff 실시간 경과와 구분|
|공유/기존 캡처|저장소·이미지renderer·안전·HTTP 등 관련 회귀는 실행. 원본/공유DTO 자동 복사 변경 없음|아래 공유/하루초안 UI4개 실패를 유지. 원음 실제 재녹음·재생은 이전 수락 증거로 구분|

## 해결한 제품 결함과 남은 실패

C 원작성자의 수정은 root가 소유권·전후 SHA를 확인해 통합했으며 V가 제품을 직접 고치지 않았다.

- V1 차단: `MemoryPhotoDescriptions` nullable current 접근으로 compile 실패 → C fix1/실제 compile 통과.
- V2 Important: 오늘/아까의 EXPLICIT_DATE가 서버 CAPTURE_DAY와 불일치 → C fix1/날짜 계약 시험 통과.
- V3 Important: 수신 완료 손상UTF8가 IOException에 묶여 OUTCOME_UNKNOWN 분류 → C fix2/HTTP4 covering 통과.

최신 기기 실패4개는 모두 `No compose hierarchies found`다.

|클래스/메서드|실패 지점/남은 검증|
|---|---|
|`ShareFlowScreenTest.visitPhotoCaptionChoicesPreviewAndBackKeepOnlyExplicitShareValues`|첫 해당 UI scroll; 공유 선택/미리보기/back 연속 경계|
|`ShareFlowScreenTest.noIncludedVisitsDisablesPreview`|52행 scroll; 포함 방문0의 화면 제약|
|`MemoryVaultScreenTest.savedStateRestorationWhileUnknown_keepsBufferHiddenUntilParkedResume`|73행 scroll; UNKNOWN 복원/수동 재개|
|`MemoryVaultScreenTest.stoppedParkedEditorDoesNotAutomaticallyReopenAfterDrivingAndParkingInBackground`|93행 touch; background 주행 전환 후 편집기 자동 재개 금지|

두 기존 시험 파일의 bytes는 기준 HEAD와 동일하다. 공유 UI 시험은 ShareSelect/Preview를 직접 조립하므로 새 기억 연결 entry를 직접 클릭한 경로는 아니다. 하루초안 시험은 공통 MemoryVaultScreen을 사용하며 새 entry가 합성 viewport에 추가됐다. Application에는 새 내비/runtime/DB 조립이 있으므로 공통 초기화가 host lifecycle에 간접 영향을 주었을 가능성을 이 결과로 배제할 수 없다. 정상 제품 entry와 새 Compose13개 통과도 이4개 경계를 대신 증명하지 않는다. 과거 [NoRoot 이슈 #2](https://github.com/chowonbhin/Pleos/issues/2)와 유형은 유사하나 원인 해결/무관함/포괄 면제를 주장하지 않으며 재시도하지 않았다.

기존 무음20초 timeout 실패와 과거134개132PASS/2FAIL은 별도 이력으로 유지한다. 이번146개는 SDK probe2개를 제외하고 새13개를 더한 범위다. Stage7은 위 핵심 근거를 인계할 수 있지만 무조건적인 통합 GREEN·실제 내비 수락·최종 데모 준비 완료 판정은 보류한다.

## 실행·열람 방법

일반 제품 입구는 같은 서명 APK 설치 후 P·속도0의 **기억 저장소 → 아래로 스크롤 → 기억 연결 → 기억 연결 열기**다. V는 사용자 자료를 분석시키지 않기 위해 마지막 열기 버튼 전의 안내까지만 실제 제품 데이터로 확인했다. 실제 제안→확정은 아래 격리 합성 fixture와 production 화면/DB로 검사했다.

재현이 필요할 때만 [SETUP](../SETUP.md)·[TESTING](../TESTING.md)으로 기기/서명/세션/포트를 확인한다. 현재 사용자를 다시 확인하고 기존 데이터·포트/세션을 덮어쓰지 않는다. 기존 private session file을 가진 저장소 루트를 `SESSION_ROOT`로 지정한다. 새 키/토큰을 코드에 넣지 않는다.

```sh
backend/.venv/bin/python scripts/fixtures/stage7_memory_transport_server.py \
  --repo-root "$PWD" --session-root "$SESSION_ROOT" --max-seconds 300
```

이 fixture는 기존18761 세션과 합성 입력만 사용하고 실제 OpenAI client를 생성하지 않는다. 기존 v1 fixture도 유지한다. 해당 전용 serial에 `reverse --no-rebind tcp:18761 tcp:18761`를 준비한 뒤 아래 지정 범위를 실행한다.

```sh
"$MV_ADB" -s "$MV_SERIAL" shell am instrument --user "$MV_USER_ID" -w -r \
  -e package app.memoryvault.memory \
  app.memoryvault.test/androidx.test.runner.AndroidJUnitRunner
```

13개 핵심 검사를 골라 보는 명령이며 이번 관련 전체146개 실행의 대체나 새 실행 성공 증거가 아니다. 마지막 원시 근거는 현재 checkout의 ignored `.local/verification/stage7/`에 있고, 개인 로컬 파일이 없는 새 환경에서는 위 fixture·소스·계약으로 범위를 이해할 수 있다.

검증 종료 시 fake 서버는 FIXTURE_STOPPED로 정상 종료했고 새reverse18761은 제거했다. 기존forward8581→8088은 보존했으며 MainActivity가 resumed이고 제품 VoiceMemoService는 없었다. host microphone off, 사용자 권한/기본앱/경로 무변경이다. 이후 source/APK 변경은 해당 근거의 영향 범위를 다시 확인해야 한다.


## 수동 내비 진단의 실행 경계

`Stage7NavigationObservationTest`, `Stage7NavigationTransportProbeTest`, `Stage7NavigationRealRouteTest`, `Stage7NavigationArrivalTest`, `Stage7NavigationLocationComparisonTest`는 root 후속 진단이다. 일반 전체 회귀에 무조건 포함하지 않는다. 마지막 세 검사는 공개 시험 목적지 경로 또는 외부 GPS 조율이 필요하며, Arrival은 실제 도착을 재현하지 못하면 실패하는 검사를 그대로 보존한다. Transport/LocationComparison은 Application provider를 해당 시험 프로세스에서 닫으므로 다른 기능 검사와 분리해 실행하고 종료 후 정상 앱을 다시 연다. `Stage7LifecycleObserver`는 요청 시에만 listener로 지정하는 관측 도구다. 위 진단의 PASS는 각 assertion의 제한된 의미만 가지며 위치 변경·도착 성공을 대신하지 않는다.
