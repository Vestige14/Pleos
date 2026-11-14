# Stage7 Memory Links Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Latest user exceptions below override sequential execution and duplicate/TDD verification execution.

**Goal:** 사진 단독 분석과 STT·목적지·기록의 근거 기반 연결, 주차 중 사용자 검토를 구현한다.
**Architecture:** 계약을 먼저 동결하고 A backend/B내비/C앱을 별도 branch/worktree에서 병렬 구현한다. root만 공통 조립을 수행하고 V 한 명이 모든 검증을 담당한다.
**Tech Stack:** 기존 Kotlin/Compose/Room/WorkManager, Pleos NaviHelper, Python/FastAPI/OpenAI Responses. 새 framework 불필요.
**Spec:** [승인 설계](../specs/2026-11-15-stage7-memory-links-design.md), [공유 계약](../specs/2026-11-15-stage7-memory-contract.md).

## Global Constraints

- 최신 사용자 승인: 3개 독립 구현 병렬; 결과물 후 검증 작업자1명이 모든 검증 전담. 구현자/root는 빌드·suite·기기/API 검증을 중복 실행하지 않는다. 이 단계의 developer TDD 실행 및 별도 per-task reviewer/whole-branch reviewer 중복은 V 단일 소유로 대체한다.
- 개발자 A/B/C는 제품 코드+검증 사례 인계만 작성한다. 검증 코드는 V가 담당한다. 구현자에게 테스트 통과를 요구하지 말고 NOT_RUN_BY_DESIGN을 보고한다. 제품 오류 수정 후 V만 영향 범위를 재실행한다.
- 작업 전 AGENTS/README/HANDOFF/docs모든MD·PDF 원문을 읽고 최신 설계/계약을 우선한다. 오래된 승인 경계는 이번 실행 지시로7단계만 갱신됐다.
- 키·세션·예산·사용자 원음/사진을 읽거나 로그/Git에 복사하지 않는다. 구현자는 서버/실API/ADB/마이크/권한 변경 금지. 실제 모델은 기존 Terra/low/default, fallback금지.
- 사용자 원문/수동확정/기존사진/공유제외/주행안전을 보존한다. 원음전송 없음. 사진만의 분석에 다른맥락 없음. 10/9사진+11/1오늘발화는 연결금지.
- one writer per file. root owns docs/common contract/Gradle/Manifest/AppContainer/MainActivity/common Screen/MemoryVaultUi/AppDatabase and schema integration; 각 worker는 exactpatch 요청만 한다.
- Git/브랜치/commit/merge/push는 root 소유. 구현자는 수정 완료 후 동결·보고하고 Git mutation 없음. subagent/reviewer를 직접 만들지 않는다.
- agent request: Sol max Standard/Fastoff; main Astra high. saved config와실제backendtier를혼동하지않는다. 이 개발 지시만으로 새 main push/Release/Stage8는하지않는다.

## Review Focus (V가 한 번 소유)

1. 다른 날짜/모호한 시간/시각 없는 최근 목적지가 같은 경험으로 연결되지 않는다 → C time scope + A server validation.
2. 이미지 request에 STT/내비/EXIF/파일명/개인화맥락이 유입되지 않는다 → A transport capture + C reencode.
3. 사용자 수정/취소/재시작 뒤 늦은 응답이 연결과 원문을 덮어쓰지 않는다 → C DB/runtime integration.
4. 후보/위치/권한/내비지원 없거나 API실패에도 원본 보존·무한호출 없음 → A/C focused tests + B service gap.
5. 공유/주행 gating이 새입구에서 유지된다 → 최종기기 흐름. 기존SDK무음 실패를 신규 성공으로 숨기지않는다.

### Task 1: A — 이미지 단독 분석 및 기억 연결 backend

**Files:** Create `backend/app/memory_v2/{__init__,schemas,photo_service,association_service,openai_adapters,routes}.py` (필요한 작은 내부파일은 같은prefix만). Modify `backend/app/main.py`, `backend/pyproject.toml`, `backend/requirements.lock`, `backend/.env.example`, `backend/README.md` only for new routes/config/dependency documentation. Existing adapter/service changes require request; import reusable types/functions instead. No backend/tests edits (V owner).
**Interfaces:** exact v2 routes from shared contract; reuse existing bearer auth/idempotency/error/usage primitives; preserve create_app callers and /v1/memory-links. supply fake adapters via constructor for V. Versioned new functions/types stay inside memory_v2.
**Dependencies:** shared contract only; C can work against the documented response.
**Acceptance:** imageonlymodelinput, boundedimagebody/cache, temporalcandidatecheck, validID/span/strictresponse, empty0calls, low/default/admission/usage preserved. Existing v1 remains usable.
- [ ] Read own brief/spec/contract/required docs; inspect relevant backend boundaries.
- [ ] Implement strict photo and association schemas/routes plus bounded/authenticated request handling.
- [ ] Implement photo-only adapter and description result; image input support is not claimed without V live evidence. If decode needed add narrowly pinned public image dependency; no local installation/build.
- [ ] Implement candidate-only association and temporal/response validation, reuse idempotency/usage primitives without shadow copies of whole old implementation.
- [ ] Hand off exact changed path list, inspection steps, notable decisions, test cases for V and all NOT_RUN; stop writing.
**V tests:** image request sentinel never contains unrelated text/coords/date; metadata strip boundary; datecounterexample; empty0call; mismatchedcontext/candidate/span; malformedimage/response; sameid replay; existingv1 compatibility. Commands owned by V: focused `python -m pytest backend/tests/test_memory_v2*.py`, full backend once at final boundary.

### Task 2: B — Pleos 내비 근거 수집

**Files:** Create `app/src/main/java/app/memoryvault/navigation/**`; optional new `docs/features/navigation-evidence.md`. No shared contract/Manifest/Gradle/DB/test edits; prepare exact integration-request.patch for required dependency/permission and source construction.
**Interfaces:** NavigationEvidenceProvider from root contract; `PleosNavigationEvidenceSource(context)` start/close/snapshot/status. C receives provider injection. API evidence links in spec.
**Dependencies:** shared types only; no A/C file writes.
**Acceptance:** current/recent/destinationchange/arrival asynchronous handling, source/time separation, no route mutation, bounded local observation retention, no invented timestamp/location, connectionfailure/empty/cancel/close safe. NAVI_ROUTE only unless primary official contract proves another required permission and root approves routine integration.
- [ ] Read required docs/brief/contract; confirm official SDK and provider requirements without device execution.
- [ ] Implement provider adapter and lifecycle/observation records; local bounded persistence inside navigation module (no new mainDB dependency). Keep recent-undated separate from observed arrival.
- [ ] Supply exact dependency and normal permission patch to root once, with primary API references. Do not install/run/build to test it.
- [ ] Hand off source, pending actual support check, smallest fake and real getter scenarios for V; stop writing.
**V tests:** fake callbacks/sourceclock/empty/unavailable/release; actualgetter once on dedicated AVD, no path change or repeated unsupported polling. Existing user route untouched.

### Task 3: C — 연결 저장·시간 후보·Android 흐름/주차 검토

**Files:** Create `app/src/main/java/app/memoryvault/memory/**`, `app/src/main/res/values/memory_strings.xml`. May narrowly modify `data/AiLinkQueue.kt`, `data/Daos.kt`, `sync/MemoryLinkRuntime.kt` only to reuse queue/transaction integration; existing public v1 behavior must remain. Shared AppDatabase/schema/MainActivity/AppContainer/Manifest/MemoryVaultScreen/MemoryVaultUi/Gradle changes go in exact integration-request.patch for root. No test files (V owner).
**Interfaces:** consumes v2 JSON and NavigationEvidenceProvider; produces MemoryReviewActivity + MemoryAssociationRuntime and new DAO/entities with proposed common migration patch. Read existing Note/Photo/Visit immutable sources, no copying/moving original files.
**Dependencies:** contract fixed; A/B implementations need not be present to write C. Constructor injection/defaultempty provider avoids importing B concrete class; protocol shapes follow contract.
**Acceptance:** independent source type classification; eventdatefilter including10/9 vs11/1; EXIF-free boundedimage and cache; source/description/ID mapping; no source lost ifAPI fails; user review/confirm/manual correction/reject/unlink/reanalyze and stale protection. New notes use one analysis path, not v1+v2 duplicate calls. Shared image excludes rawnote/privateevidence unchanged. PARKED/foreground/generation safety.
- [ ] Read required docs and current queue/repository/safety/session interfaces; report only consequential interface ambiguities to root.
- [ ] Implement local simple association entities/DAO and candidate/time resolver. Prefer additive same-DB migration for transactional state, no destructive schema or genericgraph framework.
- [ ] Implement photo sanitation/cache and authenticated bounded v2 HTTP using existing endpoint/session; preserve URI permission failure and data limits.
- [ ] Implement queue/runtime integration using existing ownership/retry policy as far as compatible; no silent lease/OUTCOME_UNKNOWN regressions or duplicate paid requests. Existing confirmed records never auto-unlock.
- [ ] Implement minimal parked review UI and explicit actions with saved progress. Provide exact common assembly/migration patch; root applies after coordinating source ownership.
- [ ] Hand off changedpaths, runnableinspectionsteps, notrunchecks, normal/failure examples for V; stop writing.
**V tests:** date cases/Unicode; sanitizedphoto; candidate0; ownuserdecision/v1data preserved; Room migration/restart; HTTP success/fail; one paidpath; reviewUI/state gates. New tests under test/memory and androidTest/memory owned by V.

### Task 4: root assembly → V single validation owner

**Files:** root applies reviewed exact common patches and combines only owned paths; no worker edits overlapping files. V owns new/affected tests, verification fixture and `docs/verification/stage7-memory-links.md`; product fixes go back to responsible owner.
**Interfaces:** freeze combinedsource and artifacts once for device acceptance. Transfer A/B/C reports and product ownership stop confirmation before V starts.
- [ ] root collect implementation reports and integrate disjoint changes on feature/07-memory-links, resolve only common assembly; do not run duplicate build/test suites.
- [ ] Dispatch ONE fresh independent Sol max V with approvedspec/contract/alltaskreports/exactdiff and knownbaseline failures. V performs test creation, builds, code review and actual acceptance; no secondary reviewer or subagent.
- [ ] V runs focused core cases first, complete relevant backend/Android checks once at integration, then dedicated device coreflow once; source freeze while devicechecks. Failure→ownerfix→coveringcheck only.
- [ ] V verifies publicSDK support and actualAPI with synthetic inputs only under the pre-existing local total budget. Coordinate credential supply without printing; unresolved API/model access must be reported, no fallback.
- [ ] root reads V result, accepts/requests focused fix, updates HANDOFF/status with executableinspectionsteps/evidence/limits, performs localGit and necessary secret audit. No rerun of V tests.
- [ ] Report Stage7 result and stop before Stage8/newremote push. Preserve existing Stage5/6 worktrees and diagnostics.
