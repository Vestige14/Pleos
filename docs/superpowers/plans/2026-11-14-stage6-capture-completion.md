# Stage6 원본 음성·선택적 위치 저장 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. 이미 승인된6단계 남은 구현을 실행한다. 사용자 최신 지시는 검토 대기 대신 남은 구현 우선이다. 원본음성/STT 저장·위치없음 허용은 확정 요구이며 반복 설계 승인 질문을 만들지 않는다. 기능/검증 완료 뒤 단계 경계는 유지한다.

**Goal:** 명시적 녹음 한 세션의 원음과 STT 원문을 함께 영속 저장하고, 캡처 위치가 유효하지 않으면 위치 없음으로 저장한다.
**Architecture:** 단일 AudioRecord의 PCM16LE/16kHz/mono를 private WAV 저장과 Pleos ON_DEVICE sendAudio에 동일하게 전달한다. voiceSessionId로 기존 Note와 private capture metadata를 연결해 현재 메인 Room schema와 AI 요청규격을 보존한다. 위치는 foreground 캡처 시점에 허용된 표준 LocationManager의 신선한 fix를 best-effort 조회하고 미지원/거부/stale는 명시적 없음이다.
**Tech Stack:** 기존 Kotlin/coroutines/Java IO/Android AudioRecord/LocationManager/Pleos SpeechToText3.3.1. 새 외부 서비스·SDK 의존성 없음.
**Spec:** 사용자 원본음성+STT 저장 및 선택적위치 정정, docs/HANDOFF.md 최신계약, /private/tmp/pleos-stage6-capture-recon.md.

## Global Constraints
- 기존 사용자 명시시작·DRIVING 확인·UNKNOWN/generation/중복세션/알림·마이크권한 안전계약 유지. 자동녹음/실제사용자마이크시험은시작하지않음.
- 캡처최대15초,최종인식deadline20초 유지. 저장시작후deadline과취소의기존계약을무시하지않는다.
- request()와 AudioRecord를동시에사용하지않고원음은자동외부업로드하지않음. SDK는ON_DEVICE/Korean만,SERVER/HYBRID fallback금지.
- 위치없음은원음/STT저장차단아님.시드/내비목적지/오래된좌표/(0,0)로빈값대체금지. 좌표0자체는유효할수있으므로범위검증으로별도판정.
- 사진/다중기억연결7단계·UI다듬기P2·원격push/Release범위아님. API누적상한은로컬기존장부그대로.

## Decisions / Rulings
Ruling: 단일PCM을파일과STT에분기한다 — 공식sendAudio입력과원음요구를함께충족하고이중마이크경쟁을피한다 — 실제Pleos입력형식/라우팅이안되면합성PCM spike결과로원인을좁혀야하며자동SDK대체는하지않는다.
Ruling: 메인Note schema를바꾸지않고voiceSessionId로private 음성manifest를연결한다 — 기존유일sessionID로연결할수있고진행중AI계약과충돌하지않는다 — 파일과DB는원자트랜잭션이아니므로저장전후장애복구를시험하고불완전상태를성공으로표시하지않는다.
Ruling: 완료된원음은STT실패시복구가능한pending/failed상태로보존하고명시취소/UNKNOWN으로취소된미완료녹음은해당세션임시파일만정리한다 — 정상녹음데이터유실과사용자가취소한기록보존을구분한다 — 불완전기록상태가남을수있으므로재시작조회/상태표시와자동유료재전송없음을검증한다. 기존사용자파일은삭제하지않는다.
Ruling: 캡처위치는표준LocationManager의현재시점30초이내유효fix를선택하고짧은조회실패는없음으로종료한다 — 미지원상태인PleosFusedSDK추가없이사용자선택적위치요구를구현한다 — 제공자에따라자주없음일수있으며실제지원검증을통과한경로만출처/시각과저장한다. 새런타임권한부여는정상주차UI와사용자시험조율을거친다.

Ruling: 신규 production voice Note는 실제 wall-clock을 사용하고 기존 typed/replay 시계는 유지한다 — 실제 캡처시각과 시연고정시각을 구분한다는 승인 계약 — 신규 기록에 시연일 방문 후보가 없을 수 있으므로 원음 화면에서 실제시각/STT를 확인하며 날짜를 위조해 연결하지 않는다.

## Review Focus
- final callback과stop/cancel/release 경합:같은원음과텍스트가1회만저장되고마이크1개정리.
- 디스크/manifest/DB실패·프로세스재시작:완료원음보존,중복Note없음,부분저장을Saved로오표시하지않음.
- 실제read길이/홀수PCM/초과15초:동일byte전송,파일header일치,상한준수.
- 위치권한거부/미지원/미래·stalefix:위치없음으로저장계속,좌표가다른세션으로새지않음.
- SDK초기화늦음/동기final/인식실패:request0회,취소후마이크재시작0,무조건fallback없음.

### Task6-C: 원본 캡처·private 저장·기존음성흐름연결
**Owned:** app/src/main/java/app/memoryvault/platform/capture/** 신규; domain/VoiceMemo.kt,platform/SpeechInput.kt,service/VoiceSessionController.kt 및그직접test/androidTest. 신규private store는capture하위. 기존PleosSpeechInput/SpeechSdk/AndroidPleosSpeechSdk는보존하고새PCM adapter로교체주입한다.
**Root integration-owned:** AppContainer.kt,AndroidManifest.xml,resources/backup설정,필요한최소UI상태안내는patch요청. data Entities/Dao/MemoryRepository/DB schema 및 sync/backend는변경금지.
**Interfaces:** 새 CaptureArtifact는sessionId,privateAudioPath,PCMformat/byteCount/hash,capturedAt,transcript상태,optional location(source/fixTime/observedTime/accuracy)를담고noteId연결은저장성공후확정한다. 새PcmSpeechInput은기존SpeechInput을구현하며final전원음file준비가끝나야한다. capture metadata store는prepare/commit/findBySession/recover 경계를제공하고note저장콜백과연결한다. 생성자세부서명은소유자가기존호출부와맞춰보고하되공통파일은직접수정하지않는다.
- [ ] 기존동결fix2/원문과직접음성경계를읽고소유구현시작. 실제PCM길이/WAV/저장복구/위치없음/latecallback/취소경계의JVM실패검사를작성하고의도RED관측.
- [ ] 최소private store와단일PCM source→file/STT coordinator구현후focusedGREEN. 실제AndroidAudioRecord와ON_DEVICEsendAudioadapter를연결;실제마이크호출은root승인된시험때만.
- [ ] 기존VoiceMemoUseCase의성공·취소·deadline·중복보호를유지하며새artifact저장흐름연결. 기존SpeechInput fake는호환하되production은원음실패를텍스트만성공으로숨기지않음. 실제캡처시각과시연고정시각을구분.
- [ ] root에AppContainer/Manifest/최소상태안내정확patch요청. 위치정상runtimegrant요청은주차시명시적조작에한정;background권한추가없음.
- [ ] 합성PCM/가짜위치기반Android시험작성·APK동결후root기기검증요청. 실제sendAudio무마이크spike와사용자조율된실제녹음재생/텍스트일치검증은분리.
- [ ] testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest 및직접수락근거/미확인범위를인계. 리뷰는task완료때1회독립수행하며작은파일별로새review를반복하지않음. Gitcheckpoint는root만조정.

### 총괄 병행: 실제API·세션·비용 수락
- [ ] root로컬env/장부를값노출없이확인하고지원모델/endpoint·admission/usage·호출상한·오류시차단준비.
- [ ] 합성텍스트12개는기억기록의기존후보연결/없으면저장만으로평가. 새사진해석/음성업로드7단계는추가하지않음.
- [ ] 승인모델실계정접근/정상Keystore세션/실제transport/재접속/사용량을기존예산내확인. 모델자동fallback금지.
- [ ] 5+6통합하고필수검증·독립최종검토/실행결과보고후단계경계중단. 원격게시별도승인유지.
