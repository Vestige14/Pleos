# STT Utterance Completion Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans. User-selected root-only execution/review overrides a fresh reviewer dispatch.

**Goal:** 전체 원음을 보존하며 여러 발화의 STT 결과를 빠짐없이 순서대로 저장하고 릴리즈 후보를 준비한다.
**Architecture:** PcmUtteranceRanges가 내부 쉼 구간에서 PCM 범위를 분리한다. PcmSpeechInput은 구간별 SDK 수명과 세대를 관리해 마지막 구간 성공 뒤에만 전체 Final을 낸다.
**Tech Stack:** Kotlin, coroutines, Pleos ON_DEVICE STT3.3.1, Room, JUnit4.
**Spec:** `docs/superpowers/specs/2026-11-15-stt-utterance-completion-design.md`

## Global Constraints

- 원음·인식 전문은 local-only; APK/assets/Git/클라우드에 넣지 않는다.
- root 단일 소유, 기존 feature/08-demo-rehearsal 및 미커밋 작업 보존. 앱 공통UX·backend·DB schema 불변.
- 원래15초 캡처/20초 전체 timeout, 주행 안전 취소를 유지. partial success 금지.
- 원격게시/릴리즈태그/main병합은 별도 확인. P1/P2 착수 없음.

## Review Focus

- 한 구간/짧은 PCM과 내부 쉼 구간이 있는 여러 발화의 저장 원문/바이트 보존.
- 첫 구간 성공 후 다음 구간 오류/빈 결과가 잘못된 성공 Note를 만들지 않음.
- SDK 해제 중 늦은 결과 및 중복 콜백이 다음 구간에 섞이지 않음.
- 두 번째 구간 처리 중 USER/UNKNOWN 취소와 전체 deadline이 자원을 정리함.
- 큰 배경소음·약한 발화에서 보수적 분할의 한계를 정답 주입으로 숨기지 않음.

## Task 1: 구간 인식과 보존

Files: 새 `app/src/main/java/app/memoryvault/platform/capture/PcmUtteranceRanges.kt`, 수정 `PcmSpeechInput.kt`, 시험 `PcmUtteranceRangesTest.kt`, `PcmSpeechSegmentsTest.kt`.
Interfaces: split(pcm: ByteArray): List<IntRange>, 범위는 byte 단위 inclusive·샘플 경계·전체 입력 연속 보존. 외부 PcmRecognitionEngine/SpeechInput 계약 불변. 실기기 수명 보완은 `PcmEngineCleanup.kt`, `AndroidPcmRecognitionEngine.kt`, `PcmEngineCleanupTest.kt`에 한정한다.

- [ ] 실제 SDK 진단 근거 고정(첫 완료 뒤8초 후속없음, 뒤쪽 구간 각각 인식 성공).
- [ ] 회귀 시험 추가: 다중 발화가 한 결과로 저장됨/분할 바이트연속/늦은구간콜백/중간오류·빈값/취소·deadline. 먼저 실패 확인.
- [ ] 분할과 순차SDK 처리 최소 수정. 단일구간 기존동작 유지.
- [ ] focused 시험 GREEN 뒤 전체 `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` exit0 확인.

## Task 2: 실제 재확인 및 릴리즈 후보

Files: 기존 수동 `Stage8UserAudioTest.kt` 활용(개인자료 asset 추가 없음), `docs/verification/stage8-stt-fix.md`, `docs/HANDOFF.md` 및 local-only 근거.
Interfaces: Task1 제품APK를 설치 후 기기 제품해시·SDK로그보호를 확인. 같은3개 PCM/새배치/각1회.

- [ ] 기존 원음과 기기 저장 SHA 동일, 전체 발화 결과·날짜·DB재열기 확인. STT 인식 오류와 누락 해결을 구분.
- [ ] 영향있는 기존 capture persistence와 synthetic SDK 시험으로 저장/단일발화 회귀 확인.
- [ ] root 별도 코드검토, 소스·산출물·비밀정보 점검, 정확한 로컬 변경만 checkpoint commit.
- [ ] 실행 가능한 후보 APK/설명/남은 조건 보고. AI 연결검증 또는 실API가 필요하면 기존 허용 범위를 확인하며, 완료 안 된 릴리즈에는 태그를 붙이지 않는다.
