# 다음 개발자·에이전트를 위한 인수인계

갱신일: 2026-11-14 (Asia/Seoul). 이 문서는 **매 push 전에 갱신하는 작업 인수인계**다. 아래 상태는 특정 시점의 기록이며, 작업 시작 시 실제 브랜치·커밋·파일과 대조한다.

## 1. 작업 시작 시 반드시 읽을 자료

루트 `AGENTS.md`, `README.md`와 **`docs/` 아래의 모든 Markdown·PDF를 읽고** 구현에 착수한다. 파일 목록만 확인하거나 이 문서의 요약만 읽는 것으로 원문 확인을 대신하지 않는다. PDF도 실제 내용을 읽는다. 도구 제약으로 읽지 못한 자료는 파일명과 미확인 범위를 알리고, 해당 요구사항을 추측해 구현하지 않는다.

권장 순서는 다음과 같다.

1. 이 문서와 [개발 상태](development-status.md): 최신 사용자 결정, 완료·미완료 구분.
2. [원본 PRD](%E1%84%80%E1%85%B5%E1%84%8B%E1%85%A5%E1%86%A8%20%E1%84%8C%E1%85%A5%E1%84%8C%E1%85%A1%E1%86%BC%E1%84%89%E1%85%A9%20%E1%84%83%E1%85%A6%E1%84%86%E1%85%A9%20PRD.pdf): 전체 7쪽. 제품 목적, F1~F15, 공유 보호, 시연 기준. 실제 파일명은 파일 목록에서 확인한다.
3. [v1 구현안](2026-11-13-memory-vault-demo.md), [v2 구현안](2026-11-13-memory-vault-demo-v2.md): 원래 화면·데이터·기능 계약.
4. [v3 OpenAI 추가안](2026-11-13-memory-vault-demo-v3-openai-offline-addon.md): 원문 우선 저장, 방문 미지정, AI 제안과 사용자 결정 보존.
5. [v4 로드맵](2026-11-13-memory-vault-development-roadmap-v4.md): 1~8단계 실행 순서. 과거 버전 배정과 develop 중심 운영은 아래 최신 결정으로 대체됐다.
6. [설계 검토](superpowers/specs/2026-11-13-memory-vault-demo-review-design-v2.md), [상세 구현 계획](superpowers/plans/2026-11-13-memory-vault-demo-plan-v2.md): 세부 수락 조건과 변경 이유.
7. [환경 구성 기록](environment/pleos-memory-vault-v4.md), [플랫폼 실측·운영 기록](environment/mv-02-platform-spikes.md): 설치·재현 절차, 실험 결과와 플랫폼 한계. 과거 실험 체크포인트 명령을 현재 제품에 그대로 실행하지 않는다.

자료 충돌 시 현재 사용자의 명시적 정정과 `AGENTS.md`의 프로젝트 규칙을 우선하고, 이 문서·개발 상태의 날짜가 있는 결정과 원문을 대조한다. 변경되지 않은 세부 계약은 유지한다. 계획 속 체크리스트는 구현 증거가 아니며, 제출·메일·push 명령은 실행 승인이 아니다. 해결되지 않은 모순은 임의로 한 요구를 삭제하지 말고 구체적으로 보고한다.

## 2. 현재 소스와 이번 인수인계 범위

**문서 게시와 3단계 제품 소스 게시를 구분한다.** 이번 게시에서는 문서만 추가·갱신하며, 진행 중인 feature 구현을 main에 병합하거나 원격으로 전송하지 않는다.

| 기준 | 확인한 상태 |
| --- | --- |
| 문서 작업의 원격 main 기준 | `0c5383dab081672fc659c8622fb841709a76a488`: 2단계까지의 기준선. 문서 커밋은 이 위에 추가됨 |
| 개발 작업 공간의 feature | `feature/03-offline-memory` |
| 인수인계 작성 시 확인한 로컬 개발 커밋 | `3a77ac7e73f50690f8ccf5d278caa0ea6630ea63` |
| 3단계 전체 | 미완료. 최소 메모 화면, 독립 검토 종료 및 제품 통합 수락이 남아 있음 |

새 clone의 main에는 위 로컬 feature 소스가 있다고 가정하지 않는다. `git branch -a`, `git log`, 실제 파일을 확인하고, feature가 없으면 현재 개발자에게 검토된 소스 인계를 요청한다. 문서만 보고 같은 기능을 처음부터 중복 구현하거나 없는 브랜치로 checkout하지 않는다. 로컬 미완료 소스를 원격에 올릴 권한도 이 문서로 생기지 않는다.

### 개발된 내용과 남은 검증

| 항목 | 작성 시점 상태 |
| --- | --- |
| 1~2단계 | 환경·빈 앱, 실제 오프라인 STT·차량 상태·주행 제한 실험 완료 기록이 있음 |
| 3A 저장 코어 | Room·시드·직접 메모·음성 원문 원자 저장·세션 중복 방지·AI 대기 데이터 구현 |
| 3B 세션 코어 | 시작 조건, 제한 시간, 취소, 유효 최종 원문 1회 저장 구현 |
| 3D 차량 상태 소스 | lifecycle·UX·기어·속도 복합 판정, 실패 시 잠금 구현·검토. 실제 API 권한 거부와 P/D/R 변화 확인 기록이 있음 |
| 3E 실제 음성 서비스 | `4757573`에 SDK·알림·서비스·저장 연결 구현. 독립 검토의 안전 상태 누락 문제를 `3a77ac7`에서 수정했으며, 작성 시점의 수정 재검토는 미완료 |
| 최소 하루 초안 UI | 작성 시점 MainActivity는 초기 안내 화면. 직접 메모 추가·수정, 저장 음성 메모 표시와 안전 전환 연결이 남음 |
| 제품 통합 | 실제 녹음→Room 저장→주차 화면 조회→프로세스 재실행 복원을 한 흐름으로 수락하지 않음 |

3E의 최초 변경 범위 검사 126개, 수정 범위 검사 99개 통과는 당시 작업 기록에 있는 **서로 다른 범위의 과거 결과**다. 합산하거나 현재 전체 제품 검증으로 표현하지 않는다. 별도 실험 앱에서 알림 버튼→비어 있지 않은 STT 결과·서비스 종료·내비 화면 유지가 관찰됐지만, 그 실험은 원문을 버렸으므로 제품 저장 성공의 증거가 아니다. 이번 문서 작성으로 앱 빌드·기기 시험을 다시 수행한 것은 아니다.

## 3. 다음 개발자가 먼저 할 작업

1. 실제 Git 상태와 현재 개발자의 인계를 확인하고 3E 수정 재검토 결과부터 확인한다. 진행 중인 구현·검토를 중복 실행하지 않는다.
2. **최소 하루 초안 화면**을 구현한다. 시연 방문 3개, 주차 중 직접 메모 추가·수정, 저장 음성 메모 읽기, 방문 미지정 메모의 연결 분석 대기를 표시한다. 조회는 검색 기능 추가가 아니다.
3. 권한 안내·녹음 알림 준비·시작/취소·차량 상태를 연결한다. 주행 중 원문과 편집 화면을 노출하지 않고, 상태 변화 시 입력창·키보드·뒤로가기 경로까지 잠근다.
4. 일반 사용자가 내비에서 녹음 시작 알림에 접근할 수 있는지 확인한다. 개발 명령으로 알림 패널을 연 결과만으로 사용자 접근 성공을 주장하지 않는다.
5. 마지막에 실제 오프라인 음성 저장, 내비 유지, 정확한 녹음 상태 표시, 안전 전환·실패·권한 변경, 앱 재시작 복원·시드 중복 방지를 통합 확인한다. 필수 전체 검증·새 clone 재현·독립 검토를 마치고 실행 가능한 APK와 확인 방법을 보고한다.
6. **3단계 완료 후 중단한다.** 사용자가 명시적으로 지시한 뒤 4단계를 시작한다.

## 4. 유지할 제품·안전 계약

- 이번에는 **버튼으로 녹음 시작**. Gleo 음성 명령은 후속이며 현재 완료 조건에 넣지 않는다. 내비 화면을 유지하고 앱의 실제 녹음 상태를 알림으로 표시한다. OS 마이크 아이콘만 보고 앱 알림 검증 완료로 판정하지 않는다.
- ON_DEVICE 한국어 STT. 최종 원문을 바꾸지 않고 먼저 저장한다. 최근 방문에 강제 귀속하지 않고 방문 미지정 저장을 허용한다. 같은 세션은 한 번만 저장한다. 원본 음성 파일은 보관하지 않는다.
- 새 녹음은 신뢰된 DRIVING에서만 시작한다. PARKED에서 편집 허용, UNKNOWN에서 잠금·녹음 취소. 네트워크나 AI 결과를 기다리느라 원문 저장을 미루지 않는다.
- 녹음은 15초, 최종 인식 결과 도착은 수락 후 20초 제한. 이미 수락한 DB 저장의 완료 시각과 구분한다. 저장 성공 전 성공 안내를 하지 않고, 취소와 저장 커밋이 겹쳐도 이미 저장된 메모를 임의 삭제하지 않는다.
- `TRANSITION`은 신뢰된 동일 연결에서 유효한 P/D 전환 조합에만 최대 500ms 적용한다. 새 녹음·편집·공유는 차단하고 기존 녹음만 제한 시간 내 유지한다. 오류·단절·초기화·잘못된 값은 즉시 UNKNOWN이다. 500ms는 구현 상한이며 플랫폼 보장값이 아니다.
- 순간 UNKNOWN 후 복구가 StateFlow 병합에 묻혀도 세션이 실패를 놓치지 않도록 현재 상태 직접 조회와 `invalidationGeneration` 계약을 유지한다.
- 최초 차량 속도 권한 부재는 UNKNOWN이다. 주차가 확인된 외부 시스템 설정에서 최초 권한을 준비하고 다시 연결한다. UNKNOWN에서 주차로 가정하거나 자동 권한 창을 열어 잠금을 우회하지 않는다.
- 콜드 시작은 최대 5초 동안 차량과 DB·시드 준비를 함께 기다릴 수 있지만 그동안 마이크를 열지 않는다.
- 시연 날짜는 `2026-11-08`, 메모 시각은 `14:58`, 시간대는 `Asia/Seoul`. 화면에 시연 데이터임을 밝힌다. 제한 시간은 고정 시연 시각이 아닌 실제 경과 시간으로 계산한다.
- SDK 로그에 원문이 남지 않도록 vendor 로그 제거를 유지한다. 제품 debug도 minify 및 non-debuggable 설정을 사용한다. 실제 발화 전 빌드 DEX의 로그 제거와 결과 콜백 보존을 확인한다.
- 실제 Car API 실패와 콘솔/browser DDS 단절을 동일시하지 않는다. 별도 DDS 감시 기능을 현재 필수 요구에 추가하지 않는다. 플랫폼이 정상값으로 제공하는 상류 오류를 모두 감지한다고 주장하지 않는다.

## 5. 전체 단계와 후속 기능

첫 릴리즈는 **v0.1.0 = F1~F10 + OpenAI 방문 연결·사용자 검토 + 통합 검증**이다. 3단계 완료가 첫 릴리즈 완료는 아니다.

| 순서 | 내용 |
| --- | --- |
| 1~2 | 환경 구축·플랫폼 실험: 완료 기록 있음 |
| 3 | 오프라인 방문·메모·최소 화면·복원: 진행 중 |
| 4 | 사진 스캔·시간/위치 후보 연결·확정/해제·권한 처리 |
| 5 | 공유 선택·개인정보 제외·미리보기·PNG 저장·공유 시트 |
| 5 이후 | **전체 UI 다듬기: 6단계 OpenAI 연결 착수 전에 진행** |
| 6 | OpenAI 백엔드·인증·구조화 응답·대기 작업·재시도·중복/늦은 응답 처리 |
| 7 | 연결 제안 검토·확정·수정·거절, 사용자 결정 보호 |
| 8 | 전체 통합 검증·개인정보/비밀정보 점검·APK·영상·README·첫 릴리즈 |

P1(F11 전체 배지 확장·F12 사진 이동·F13 문장 다듬기), P2(F14 링크 공유·F15 3D 기억 맵), Gleo는 현재 1~8단계 밖의 후속 범위다. 최소 출처·후보·확정 표시는 현재 포함한다. 실제 방문 감지, 휴대폰 연동, 계정·동기화, 기억 사이 자동 연결·회상도 이번 범위 밖이며 후속 일정·버전은 미정이다. OpenAI 방문 연결과 문장 다듬기는 서로 다른 기능이다.

과거 문서의 3/5/7/8단계별 v0.1/v0.2/v0.3/v0.4 배정, develop 중심 통합, STT 실패 시 자동 텍스트 대체, 단계별 중단 없이 계속 실행한다는 지시는 최신 사용자 정정으로 대체됐다.

## 6. 실행·검증 방법

JDK 17, Android SDK 35 / Build Tools 35.0.0, Gradle wrapper 8.13, AGP 8.13.2, Kotlin 2.2.21을 기준으로 한다. 패키지는 `app.memoryvault`, minSdk 29, targetSdk 34다. 실제 소스의 Gradle 설정과 함께 확인한다. Pleos 전용 기능은 일반 휴대폰에서 동작한다고 가정하지 않는다.

새 clone의 루트에서 macOS 예시:

```sh
git status --short
git branch -a
git log -5 --oneline
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_USER_HOME="$PWD/.local/android"
export GRADLE_USER_HOME="$PWD/.local/gradle-home"
mkdir -p "$ANDROID_USER_HOME" "$GRADLE_USER_HOME"
# 기존 local.properties가 있으면 내용을 먼저 확인하고 보존한다.
test -f local.properties || printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./gradlew --no-daemon --console=plain assembleDebug
```

공개 의존성 다운로드가 필요할 수 있다. 다른 개발자의 `.local`, 키, 캐시, AVD 데이터를 복사하지 않는다. APK는 `app/build/outputs/apk/debug/app-debug.apk`다. **main 기준선으로 빌드하면 초기 안내 앱이며, 로컬 feature 구현이 자동으로 포함되지 않는다.**

Pleos API34 전용 새 AVD, cold boot, RAM 4GB를 준비한다. [README](../README.md)와 [환경 기록](environment/pleos-memory-vault-v4.md)에 따라 사용하지 않는 ADB·emulator 포트와 실제 serial/user를 확인한다. 기존 앱 데이터 삭제나 다른 AVD 덮어쓰기로 설치 문제를 해결하지 않는다. 마이크는 사용자 발화 시험 때만 켜고 이후 복원한다. 아래는 이미 준비된 새 기기에 설치하는 예시다.

```sh
: "${MV_SERIAL:?대상 새 AVD serial을 지정하세요}"
MV_USER_ID="$("$ANDROID_HOME/platform-tools/adb" -s "$MV_SERIAL" shell am get-current-user | tr -d '\r')"
"$ANDROID_HOME/platform-tools/adb" -s "$MV_SERIAL" shell pm path --user "$MV_USER_ID" app.memoryvault
# 기존 패키지가 없는 전용 기기임을 확인한 후 실행한다.
"$ANDROID_HOME/platform-tools/adb" -s "$MV_SERIAL" install --user "$MV_USER_ID" app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/platform-tools/adb" -s "$MV_SERIAL" shell am start -W --user "$MV_USER_ID" -n app.memoryvault/.MainActivity
```

중간 수정에서는 변경 부분·직접 영향 범위·필수 안전 회귀만 검사한다. 전체 검증을 작업마다 반복하지 않는다. 3단계 마무리에는 다음 전체 명령과 실제 기기 통합·재시작 확인이 필요하다.

```sh
./gradlew --no-daemon --console=plain testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
# 아래 스크립트는 3E 소스가 인계된 브랜치에서만 존재한다.
python3 scripts/verify_vendor_log_dex.py app/build/outputs/apk/debug/app-debug.apk \
  --dexdump "$ANDROID_HOME/build-tools/35.0.0/dexdump"
```

Room 기기 테스트 절차는 [개발 상태](development-status.md)의 코어 검증 재현을 따른다. 테스트 개수와 과거 APK 해시는 특정 소스의 증거이므로 새 변경의 통과 근거로 재사용하지 않는다. 이 문서 작성 과정에서는 앱 테스트를 실행하지 않았다.

## 7. 코드 탐색 지도

다음 경로는 3단계 feature 기준이다. main에 없으면 소스 인계 여부부터 확인한다.

- `app/src/main/java/app/memoryvault/data/`: Room, 시드, 메모 저장·조회, AI 대기 데이터.
- `domain/DrivePolicy.kt`, `domain/VoiceMemo.kt`: 상태별 정책·음성 세션 계약.
- `platform/AaosDriveStateSource.kt`, `DriveReadiness.kt`, `DriveStateSource.kt`: 차량 준비·실패·전환 판정.
- `platform/PleosSpeechInput.kt`, `AndroidPleosSpeechSdk.kt`, `SpeechSdk.kt`: 실제 STT와 시험 경계.
- `service/VoiceMemoService.kt`, `VoiceSessionController.kt`, `VoiceNotifications.kt`, `VoiceNotificationReceiver.kt`: 녹음 수명·알림·시작/취소.
- `MemoryVaultApp.kt`, `AppContainer.kt`, `CaptureClock.kt`: 앱 초기화·서비스/저장 연결·시연 시각.
- `MainActivity.kt`: 최소 하루 초안 UI 연결 대상.
- `app/src/test/`, `app/src/androidTest/`: JVM 및 기기 검사. `app/vendor-log-protection.pro`, `scripts/verify_vendor_log_dex.py`: SDK 원문 로그 보호.

## 8. 매 push 전 인수인계 갱신 규칙

**코드·기능·설정·문서의 어떤 push든 이 문서를 먼저 갱신한다.** 현재 작업을 모르는 다음 개발자가 아래 내용을 이해할 수 있어야 한다.

- 본인이 무엇을 개발·수정했는지, 관련 파일·브랜치·대상 소스 커밋.
- 무엇이 실제 완료됐고 무엇은 구현만 됐거나 미검증인지.
- 실행·확인 방법, 수행한 검증과 결과, 수행하지 않은 검사.
- 남은 오류·차단 조건·결정 사항과 이유.
- **다음에 개발할 기능과 바로 시작할 작업**, 완료 조건 및 범위 밖 항목.
- 최신 사용자 정정·UI 시점·단계 중단·릴리즈 범위의 변경.

push마다 낡은 ‘현재 상태’를 수정한다. 역사 기록을 계속 붙이기만 해서 서로 다른 현재 상태가 공존하게 하지 않는다. 새 결정은 원본 PRD를 무단 덮어쓰지 않고 최신 결정으로 구분한다. 원본 `docs` 자료·운영 지침은 함께 유지한다. 링크된 필수 자료가 Git 추적 대상인지 확인한다. `.superpowers`·`.local`의 로컬 증거만 있어야 이해되는 인수인계를 만들지 않는다.

비밀값·인증 파일·키·실제 사용자 원문·광범위 시스템 로그는 문서나 PDF에 넣지 않는다. 전송할 정확한 파일·커밋·blob과 PDF 내용을 점검하고 값은 로그에 출력하지 않는다. 운영·보안 절차 자체는 보존한다. 비밀값이 발견되면 게시를 멈추고 위치·종류만 보고하며 원본/이력을 임의로 삭제·재작성하지 않는다.

push는 사용자 승인 범위 안에서만 수행하며, push 승인으로 다음 개발 단계가 자동 승인되지 않는다. 각 단계 완료 시 실행 결과와 남은 작업을 보고하고 중단한다. 진행 중인 다른 개발자의 소스·Git 작업·기기 자원과 충돌하지 않도록 조율한다.

### 이번 문서 작업의 인계

- 개발·변경: 에이전트 필수 읽기 진입점, 원본 MD/PDF 공개 묶음, 이 인수인계와 매 push 갱신 규칙.
- 제품 소스 변경: 없음. 미완료 3단계 소스를 main에 합치지 않음.
- 문서 검증: 게시 전 링크·파일 포함 여부·변경 범위·비밀정보 점검을 수행하고 결과를 게시 보고에 남긴다. 앱 검증과 구분한다.
- 다음 작업: 검토된 3E 소스 인계 및 수정 검토 결과 확인 → 최소 하루 초안 UI → 3단계 전체 통합 수락 → 보고 후 중단.
