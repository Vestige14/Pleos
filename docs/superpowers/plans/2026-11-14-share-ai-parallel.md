# 5·6단계 병렬 구현 및 통합 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. 최신 사용자가 Task1/Task2의 별도 worktree 병렬 실행을 명시 승인했다. Task별 TDD와 독립 검토는 유지한다. 구현자는 하위 에이전트를 만들지 않는다.

**Goal:** 4단계 main을 보존하며 공유 선택→PNG 저장/공유와 오프라인 메모→재접속 AI 제안을 각각 구현하고 함께 실행 가능한 앱으로 통합한다.
**Architecture:** 공유는 독립 Activity와 별도 Room 선택 저장소로 격리한다. AI는 기존 Room 대기열을 사용한 WorkManager/HTTP/응답 적용과 로컬 백엔드로 격리한다. 공통 앱 조립 파일은 총괄만 수정한다.
**Tech Stack:** 기존 Kotlin/Compose/Room/serialization, WorkManager, FastAPI/OpenAI Responses. 기존 minSdk29/target34/compile35 및 ON_DEVICE STT 유지.
**Spec:** `docs/2026-11-13-memory-vault-demo-v2.md` F8~F10, `docs/superpowers/plans/2026-11-13-memory-vault-demo-plan-v2.md` T6/T7, `docs/2026-11-13-memory-vault-demo-v3-openai-offline-addon.md` AI2/AI3. 최신 사용자 병렬/UI순서 정정은 HANDOFF를 따른다. 최신 사용자는 우선5·6 병렬 구현·통합을 재개했다. 전체 UI 다듬기는 후속 P2의3D 기억 맵과 함께 진행하며 지금은 기존 스타일과 기능 필수 UI만 구현한다. 7·8단계 착수 전 현재5·6 결과를 보고한다.

## Global Constraints

- 원본 음성 메모를 수정하거나 삭제하지 않는다. 공유 DTO에 Note·좌표·주소·제외한 방문을 넣지 않는다.
- UNKNOWN/DRIVING/TRANSITION에서는 공유 편집·미리보기·새 저장/chooser를 허용하지 않는다. 저장 시작·MediaStore 공개 직전·chooser 직전 실제 최신 상태/generation을 확인한다.
- 공유 선택은 재스캔·화면 재진입·프로세스 재실행 뒤 유지한다. 처음 접근 가능한 후보/확정 사진을 기본 선택하지만 사용자가 제외한 항목을 자동 재선택하지 않는다.
- PNG 폭1080·높이12000·기존 렌더러 메모리/출력 상한을 유지하며 조용히 잘라 성공하지 않는다. 새 PNG에 원본 EXIF를 복사하지 않는다.
- AI 전송은 원문과 원래 날짜/녹음 시점 이전의 최소 후보 snapshot만 사용한다. 사진·정확한 좌표·주소·키는 전송하지 않는다. AI 제안은 사용자 확정과 다르다.
- 대기 작업의 실제시계·lease·attemptToken·note/user revision·contextHash를 검증하며 늦은 결과는 사용자 결정을 덮어쓰지 않는다. 원문이 이미 저장된 후 서버 장애는 원문·사진·공유 사용을 막지 않는다.
- 비밀값/예산금액은 로컬에만 둔다. 실제 API 호출은 총괄 전용; 구현자는 fake HTTP/모델만 사용한다. 키·세션 토큰을 APK/BuildConfig/로그에 포함하지 않는다.
- 기존 이슈 #2 간헐 UI 실패는 알려진 유형의 게시 예외이며 새 결함 전체를 면제하지 않는다. 릴리즈/원격 push/실제 마이크 사용은 이번 구현자 권한이 아니다.
- 기존 검토 완료 준비물을 그대로 가져오는 작업은 재구현하지 않는다. 새 구현/동작 수정은 실패 테스트→최소 구현→GREEN. 파일당 한 소유자, 다른 변경 되돌리기 금지.

## Review Focus

1. 빠르게 선택을 바꾼 뒤 앱 종료 시 마지막 선택 유실 — Task1의 저장 직렬화·재열기 검사.
2. 미리보기 후 권한/주행 상태 변경 및 취소 — Task1의 export 단계별 가드·pending URI 정리 검사.
3. API timeout 이후 비용/중복 호출 및 불명확한 응답 — Task2의 안정 오류 코드·OUTCOME_UNKNOWN 비재시도 검사.
4. enqueue 누락·만료된 lease의 늦은 응답·다른 날짜 재접속 — Task2의 실제 DB 원자성·reconciliation 검사.
5. 합친 앱에서 사진/음성 서비스와 새 Activity/Worker의 lifecycle 충돌 — 총괄의 통합 실행·기기 검사. 제품 안전 gate를 테스트 편의로 낮추지 않는다.

## 브랜치와 소유권

- 공통 기준: main `e9f40f8` + 이 기록의 로컬 문서 커밋.
- Task1: `feature/05-share-export`, `app/src/{main,test,androidTest}/java/app/memoryvault/share/**`, 신규 `app/src/main/res/values/share_strings.xml`, `docs/features/share-export.md`, 신규 `app/schemas/app.memoryvault.share.ShareSelectionDatabase/**`만 쓴다.
- Task2: `feature/06-openai-sync`, `backend/**`(비밀파일 제외), `app/src/{main,test,androidTest}/java/app/memoryvault/sync/**`, `data/{AiLinkQueue,Daos,MemoryRepository}.kt` 및 해당 AI 전용 테스트, 신규 `res/values/ai_strings.xml`, `docs/features/openai-sync.md`만 쓴다. schema 변경이 필요하면 먼저 요청한다.
- 총괄: AppContainer/MainActivity/MemoryVaultScreen/Manifest/build.gradle.kts/공통 리소스·HANDOFF·상태·계획·Git·기기·실API. 구현자는 이 공통 파일의 필요한 exact patch를 자신의 report 옆 integration-request.patch로 제안하며 직접 적용하지 않는다.
- 두 구현자는 별도 worktree/output을 사용한다. Gradle은 `/private/tmp/pleos-serialized-gradle.py`의 공용 잠금과 max-workers1로만 실행한다. ADB·에뮬레이터·서버 포트/설정·Git 커밋/브랜치/merge/push는 총괄만 수행한다.

### Task 1: 5단계 공유 흐름 완성

**Purpose:** 검토 완료 share 코어8파일에 앱에서 사용할 선택·보존·미리보기·MediaStore 내보내기/공유를 연결한다.
**Files:** 소유 share/**에 `ShareSelectionStore.kt`, `ShareSelectionDatabase.kt`, `ShareFlowController.kt`, `ShareExporter.kt`, `ShareIntentFactory.kt`, `ui/ShareFlowActivity.kt`, `ui/ShareSelectScreen.kt`, `ui/SharePreviewScreen.kt`와 관련 테스트를 추가한다. 기존8파일은 기존 동작 보존하며 필요한 수정만 TDD한다. 다른 경로 필요 시 요청한다.
**Interfaces:** 기존 `ShareDraftBuilder.build(ShareSelection,ShareInput): ShareDraft?`, `ShareImageRenderer.render(ShareDraft): ShareRenderResult`를 재사용한다. `ShareInputMapper.from(day: DayDraft): ShareInput`은 Note/주소/좌표를 넘기지 않는다. `ShareSelectionStore.load(date): ShareSelection?`, `save(selection)`는 suspend이며 별도 `share-selections.db`의 Room 행에 선택을 보존한다. `ShareFlowActivity`는 non-exported이고 `(application as MemoryVaultApp).container`의 공개 repository/driveSnapshot만 소비한다. 외부 진입은 총괄이 주차 화면 버튼으로 연결한다.

- [ ] 준비된8파일 manifest/hash·기존보고서·승인 근거를 확인하고 현재 바이트를 그대로 유지한다. renderer는 이미 구현/검증됐으며 no-op으로 되돌리지 않는다.
- [ ] 순수 controller/store 계약의 실패 검사: 첫 전체 선택, 카페/사진 제외 유지, caption trim/blank, 0방문, 재스캔/날짜전환, 저장 순서 경쟁에서 최신 선택 보존, 기존 snapshot 불변. JVM RED를 확인한다.
- [ ] 별도 Room 선택 저장소·day mapper·controller를 최소 구현한다. 메인 DB schema는 바꾸지 않는다. Compose 상태는 Activity/foreground 수명을 따른다. 날짜/선택은 보존하되 주행/UNKNOWN을 주차로 복원하지 않는다.
- [ ] Activity의 방문 스위치·사진 체크·공유 설명·미리보기와 뒤로 가기를 구현한다. 첫 선택 목록은 후보/확정 접근 가능 사진만 허용한다. 사진은 기존 안전 thumbnail 경계를 재사용하거나 자기 Activity의 foreground/PARKED/generation 경계로 읽는다. 주행·ON_STOP 때 입력/미리보기 숨김 및 작업 취소, 주차 복귀는 하루 초안으로 돌아가며 다시 열면 선택 복원.
- [ ] exporter 실패 검사를 먼저 작성한다: URI 읽기 실패, 저장 실패, 생성중 출발/짧은UNKNOWN, 권한철회, task취소→자기 pending행만 정리; 이미 공개된 파일을 다른 오류 때문에 삭제하지 않음; chooser없음→파일유지+안내; READ_URI_PERMISSION+ClipData와 올바른 MIME. 테스트용 IO/clock/drive/chooser를 주입하고 실제 Android MediaStore 검사도 준비한다.
- [ ] API29+ `Pictures/기억저장소/`, MIME image/png, IS_PENDING1→성공시0로 저장한다. 앱 minSdk29이므로 API28 분기는 추가하지 않는다. render 전/읽기 및 공개 직전/chooser 직전 최신 snapshot/generation을 재확인하고, 취소 시 반환/공개/자동chooser 금지. OS chooser 이후 타 앱 동작 통제는 주장하지 않는다.
- [ ] GREEN: `testDebugUnitTest --tests 'app.memoryvault.share.*'`, 컴파일/양APK. instrumented RED/GREEN은 총괄에 정확한 class/메서드·예상실패·소스동결을 전달하고 요청한다. 임의로 ADB 실행하지 않는다. UI NoRoot를 assertions 제거/skip으로 피하지 않는다.
- [ ] 총괄 실제 수락: 카페·p3·p6·개인메모/좌표 없는1080 PNG와 미리보기, 저장 후 공유시트, 재진입/force-stop선택보존, D/UNKNOWN/권한철회 실패정리. docs/features/share-export.md에 실행방법/미검증을 남긴다.
- [ ] 전체 소유 diff 자기검토 후 동결. 보고서에 changed files, 실제 RED/GREEN 명령/exit, hash, 실행방법, 공통 통합 patch, 열린이슈를 기록한다. Git 커밋은 총괄에게 맡긴다.

### Task 2: 6단계 앱 동기화와 백엔드 연결 완성

**Purpose:** 기존 백엔드16파일과 기존 Room AI queue를 연결해 오프라인 원문을 재접속 때 실제 방문 제안으로 처리할 수 있는 기능을 만든다. 사용자가 제안을 검토/확정하는 전체7단계 UI는 추가하지 않는다.
**Files:** 소유 sync/**에 `MemoryLinkApi.kt`, `MemoryLinkWorker.kt`, `MemoryLinkScheduler.kt`, `MemoryLinkRuntime.kt`, `MemoryLinkSessionStore.kt`, `ui/MemoryLinkSetupActivity.kt`와 관련 테스트. 기존 data/AiLinkQueue.kt·Daos.kt·MemoryRepository.kt에 필요한 트랜잭션/조회. backend 기존16파일은 보존하고 테스트 가능한 서버 실행·사용량/호출제한 인터페이스가 필요할 때만 변경. 예산금액/키는 어떤 소유파일에도 쓰지 않는다.
**Interfaces:** `MemoryLinkRuntime.start(context: Context, db: AppDatabase)`를 앱 DB 준비 후 총괄이 호출; `MemoryLinkRuntime.reconcilePendingJobs(context)`를 새 voice 저장 후/앱 시작 후 호출. Worker input에는 jobId만, 고유 이름 `link-{jobId}` KEEP+NetworkType.CONNECTED. `MemoryLinkQueue.applyResult(jobId,result,attemptToken)` 결과 APPLIED/IGNORED_STALE/REVIEW_REQUIRED. 현재 AiLinkQueue의 네이밍·엔티티에 맞추는 변경은 보고서에 정확히 명시한다. 공유 Task1은 이 API를 소비하지 않는다.

- [ ] 기존 backend manifest/hash·145개 fake 검사·독립 fix1 승인 근거를 확인한다. 코드 재구현 없이 기존 오류/응답 계약을 앱의 기준으로 사용한다.
- [ ] 계약 실패 검사 먼저: requestId/noteRevision/userRevision/contextHash echo 불일치, 후보밖 방문, 잘못된 codepoint범위·emoji, REVIEW/NO_MATCH, 불명확한 timeout, malformed본문; 개인정보/토큰/응답원문 로그 금지. Android HTTP는 기존 serialization+플랫폼 HTTP를 우선하고 필요의존성은 요청한다.
- [ ] Room lease 획득/반환/결과적용을 원자 구현: lease2분·요청30초·최대5실제시도·30초기반backoff 및 Retry-After. 실제 시스템시계 사용, demoNow 금지. 매 lease에 새 attemptToken; note/user revision·contextHash·현재token·사용자lock이 일치해야 적용한다. SUGGEST는 사용자확정이 아니며 원문이나 기존 확정 연결을 쓰지 않는다.
- [ ] WorkManager 테스트 RED→GREEN: 오프라인 API0회, 연결시실행, 중복worker호출1회, enqueue전종료후 앱시작reconcile, lease만료/교체후옛응답무시, 과거날짜snapshot그대로,429/5xx한정재시도,401/과금/설정오류중단. 서버 `OUTCOME_UNKNOWN/retryable=false`를503만보고재호출하지 않는다. 중복비용exactly-once를 주장하지 않는다.
- [ ] 기본 `HttpURLConnection` 등 취소전파 가능한 HTTP와 세션 인증을 구현한다. 서버키는 Android에 없다. 데모 endpoint는 루프백 개발서버이며 공용/LAN 바인딩·외부배포하지 않는다. 개발 build의 localhost한정 cleartext예외는 총괄이 요청patch로 적용하며 release예외 금지. 새 의존성 WorkManager 버전은 공식근거 확인 후 총괄에게 Gradle patch 요청한다.
- [ ] 별도 non-exported 주차 설정 Activity는 endpoint/마스킹된 개발세션토큰을 정상 UI로 입력·교체한다. session token은 AndroidKeystore AES-GCM으로 암호화한 앱전용저장소, 백업대상 제외. 토큰조회/로그노출/UI원문표시 금지; 주행·UNKNOWN/ON_STOP시 입력숨김. 앱 인증 실패는 명확한 고정코드 안내. 원본 OpenAI키 입력칸을 만들지 않는다. 해당 연결 설정은 데모 준비용이며 사용자 방문 제안 확정 UI와 구분한다.
- [ ] backend 필요시 사용량 hook/호출전 admission hook을 추가하되 기본키·모델·가격·예산은 없음. 금액/실제API실행은 총괄 로컬설정으로만 결정한다. hook이거절하면모델0회; SDK자동재시도0 유지. synthetic fake 검증만 수행한다.
- [ ] GREEN: 전체 backend `python -m pytest backend`/pipcheck; Android `testDebugUnitTest --tests 'app.memoryvault.sync.*'`와 해당data회귀·컴파일. Room/WorkManager instrumented시험은 총괄에 RED/GREEN 요청. 공통 Gradle/Manifest/조립은 수정하지 않고 정확한 integration patch를 보고한다.
- [ ] 총괄 실제수락: 제한된 로컬서버·전용 AVD reverse·안전한세션공급, 실제 합성12발화의정확성/보류/지연/사용량, 오프라인저장→재접속제안, 서버꺼짐에도원문보존, 늦은응답보호. 실제호출전 로컬 누적비용한도를 확인한다. 구현자는키를읽거나API를호출하지 않는다.
- [ ] docs/features/openai-sync.md에 fake/실제 검증을 분리해 실행방법·한계를 기록한다. 소스동결+보고서+통합patch를 제출한다. Git은 총괄 소유다.

## 통합과 완료

총괄은 각 결과의 독립 task 검토 뒤 통합 브랜치에 소유파일만 가져오고 공통 조립을 직렬 수행한다. task review는 구현자와 다른 새 reviewer가 맡으며 수정은 원 구현자에게 돌린다. 전체 통합 후 whole-branch 검토와 필요한 회귀/기기/실API를 수행한다. main/원격 push는 검증과 사용자 승인 범위가 확인된 뒤에만 진행한다. 이 계획은 UI 시각 디자인을 앞당기거나 7단계 전체 확인 UI를5/6에 숨겨 추가하는 지시가 아니다.
