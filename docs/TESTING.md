# 3단계 체크포인트 테스트 안내

이 문서는 다음 담당자가 **현재 소스와 자기 전용 Pleos AVD**에서 빌드·자동 검사·실제 기능을 확인하는 절차입니다. 환경 준비는 [SETUP](SETUP.md), 기준 소스와 기존 결과는 [HANDOFF](HANDOFF.md)를 먼저 읽습니다. **정상 알림센터 진입은 [이슈 #1](https://github.com/chowonbhin/Pleos/issues/1)로 미해결이며 3단계는 완료 보류**입니다.

## 1. 시험 전 기록과 대상 확인

SETUP에서 설정한 `JAVA_HOME`, `ANDROID_HOME`, 프로젝트 로컬 Android/Gradle home, `MV_ADB`, `ADB_SERVER_SOCKET`, `MV_SERIAL`, `MV_CONSOLE_SERIAL`, `MV_USER_ID`를 같은 터미널에 유지합니다. 과거 기기 번호를 복사하지 않습니다.

```sh
git rev-parse HEAD
git status --short
"$MV_ADB" devices -l
"$MV_ADB" -s "$MV_CONSOLE_SERIAL" emu avd name
"$MV_ADB" -s "$MV_SERIAL" shell getprop sys.boot_completed
"$MV_ADB" -s "$MV_SERIAL" shell am get-current-user
```

검사할 source와 변경 범위, 대상 AVD·현재 사용자, 기존 메모/시드 상태를 기록합니다. 다른 작업의 기기나 데이터를 선택하지 않습니다. 새 환경은 자체 서명키와 새 AVD를 사용하고, 기존 설치 업데이트는 같은 서명을 확인한 경우에만 수행합니다. 데이터 삭제·OS 주행 제한 해제·특권 서비스 kill은 이 시험에 포함되지 않습니다.

## 2. 자동 검사와 APK 확인

프로젝트 루트에서 실행합니다. 의존성이 준비된 환경에서만 `--offline`을 추가할 수 있습니다.

```sh
./gradlew --no-daemon --console=plain testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

Gradle 종료 코드와 `BUILD SUCCESSFUL`을 확인하고 다음 결과를 읽습니다.

- JVM: `app/build/test-results/testDebugUnitTest/TEST-*.xml`의 tests/failures/errors/skipped.
- Lint: `app/build/reports/lint-results-debug.html` 또는 XML. 오류뿐 아니라 남은 경고도 기록합니다.
- 제품 APK: `app/build/outputs/apk/debug/app-debug.apk`.
- 테스트 APK: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.

**실제 발화 전에 사용할 제품 APK**를 다음 명령으로 검사합니다.

```sh
python3 scripts/verify_vendor_log_dex.py app/build/outputs/apk/debug/app-debug.apk \
  --dexdump "$ANDROID_HOME/build-tools/35.0.0/dexdump"
```

검사 exit 0, vendor logger 호출 0, SDK 호출자의 Android log 호출 0, 실제 결과 callback 호출 보존을 확인합니다. 단순 문자열 검색이나 과거 APK의 통과를 새 APK에 대입하지 않습니다. 현재 ON_DEVICE 경로는 등록 키·OpenAI 키 없이 동작하며, 실제 서버 인식·AI 분석을 검증하는 절차가 아닙니다.

APK SHA-256도 기록합니다. macOS에서는 `shasum -a 256 <APK>`, 다른 OS는 해당 SHA-256 도구를 사용합니다. 키·원문·광범위 시스템 로그는 결과에 넣지 않습니다.

## 3. Room·Compose 기기 검사

SETUP의 설치 절차로 제품과 테스트 APK를 동일 전용 기기·현재 사용자에 설치합니다. 실제 발화가 끝나고 마이크 입력이 꺼진 상태에서 공식 콘솔을 **P·0**에 둡니다. OS가 테스트 Activity를 가리는 주행 상태로 시작하지 않습니다.

```sh
"$MV_ADB" -s "$MV_SERIAL" shell am instrument --user "$MV_USER_ID" -w -r \
  app.memoryvault.test/androidx.test.runner.AndroidJUnitRunner
```

현재 테스트 소스는 **Room 27개 + Compose 7개 = 34개**입니다. `OK (34 tests)`와 개별 실패 유무를 확인합니다. `am instrument`의 셸 종료 코드나 마지막 `INSTRUMENTATION_CODE` 하나로 성공을 판정하지 않습니다. 테스트는 in-memory DB 또는 테스트가 만든 UUID 파일 DB를 사용하며 제품의 저장 메모를 지우는 검사가 아닙니다. 실제 STT 마이크를 자동으로 시작하지 않습니다.

변경 영향이 UI에 한정되면 다음처럼 관련 그룹을 실행할 수 있습니다. 전체 수락 검사를 대체할 때는 해당 경계의 필수 검사 범위를 먼저 확인합니다.

```sh
"$MV_ADB" -s "$MV_SERIAL" shell am instrument --user "$MV_USER_ID" -w -r \
  -e class app.memoryvault.ui.MemoryVaultScreenTest \
  app.memoryvault.test/androidx.test.runner.AndroidJUnitRunner
```

실패하면 실패 메서드·지점과 당시 P/Activity 상태를 먼저 확인합니다. 기존 시험에서 한 번 발생한 초기 편집기 노드 부재는 후속 단독·그룹·전체 실행에서 재현되지 않았지만 원인이 확정된 것은 아닙니다. 재발 시 관련 검사만 분리하고 첫 클릭 직후의 UI·lifecycle을 좁게 관측합니다. 무작정 전체 검사를 반복하거나 안전 gate를 완화하지 않습니다.

## 4. 공식 콘솔과 최초 권한 준비

[공식 Virtual Console 안내](https://document.pleos.ai/docs/pleos-only/app-test-guide/virtual-console-guide)와 [차량 에뮬레이터 안내](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/use-vehicle-emulator)를 따릅니다. 비어 있는 로컬 포트 하나를 **이 전용 serial의 8088**로 연결합니다. 다른 기기의 forward를 제거하거나 덮어쓰지 않습니다.

```sh
: "${MV_CONSOLE_PORT:?선택한 비어 있는 로컬 콘솔 포트}"
"$MV_ADB" -s "$MV_SERIAL" forward tcp:"$MV_CONSOLE_PORT" tcp:8088
```

브라우저에서 `http://127.0.0.1:<MV_CONSOLE_PORT>`를 열고 이 AVD임을 확인합니다. 정상 브라우저 세션을 유지한 채 공식 UI로 기어·속도를 조작합니다. **속도를 0으로 만든 뒤 기어를 바꾸고** 목표 속도를 적용합니다. 이번 주행 조건은 시험용 AVD의 D·36km/h이며 실차 주행 중 조작 지시가 아닙니다.

먼저 P·0을 확인하고 SETUP의 외부 시스템 설정으로 CAR_SPEED 최초 권한을 준비합니다. 앱에서 신뢰된 P가 확인되면 마이크·알림 권한과 시작 알림을 준비합니다. 최초 UNKNOWN을 P로 가정하거나 앱이 그 상태에서 권한창을 열도록 고치지 않습니다. 콘솔/browser 연결 종료는 Car/VHAL 서비스 단절 시험과 다릅니다.

## 5. 주차 화면·직접 메모·초안 보호

1. 앱을 실행하고 `하루 초안`, `시연 데이터 · 2026-11-08 (Asia/Seoul)`, 녹음 시각 `14:58` 표기를 확인합니다.
2. 시드 방문은 대부도 칼국수집 12:00–13:10 → 방아머리해변 13:30–14:50 → 바다 앞 카페 15:10–16:00, 총 3곳입니다. 이미 메모가 있어도 방문이 중복되면 안 됩니다.
3. 한 방문의 `메모 추가`에서 비개인 시험 문장을 저장하고 `메모 수정`으로 바꿉니다. 직접 메모로 표시되는지 확인합니다.
4. 다른 추가 입력창에 미전송 문장을 쓰고 `닫기 · 초안 보관`을 누릅니다. 다른 방문을 열 때 초안 유지/폐기 확인이 나와야 합니다. `기존 초안 유지`와 수동 `이어 쓰기`로 원래 문장이 남는지 확인합니다.
5. 명시적으로 `초안 버리고 새로 쓰기`를 선택했을 때만 대상을 바꿉니다. 이 확인으로 저장된 원문 메모까지 삭제되면 안 됩니다.

이 단계의 ‘초안 보관’은 미전송 편집 버퍼입니다. 저장된 메모의 Room 복원과 구분하며 force-stop·데이터 삭제 뒤 미전송 버퍼의 영구 복원을 기대하지 않습니다.

## 6. 실제 오프라인 음성 시험

다음은 **일반 알림 진입의 완료 증거가 아닌 개발용 제품 흐름 시험**입니다. 실제 음성을 말할 사용자가 준비된 시점에만 진행합니다. 버튼은 사용자가 직접 누릅니다.

### 준비

- 시험용 짧은 비개인 한국어 문장을 정합니다. 원문을 외부 로그·이슈·문서에 복사하지 않고 사용자가 결과 일치를 확인합니다.
- AVD의 `hw.audioInput=yes`가 실제 적용됐고, 호스트 OS가 Emulator 실행 주체의 마이크를 허용했는지 확인합니다. 설정 변경 시 같은 AVD를 데이터 보존 상태로 재시작해야 할 수 있습니다. `hostmicon` 명령 성공만으로 실제 입력을 보장하지 않습니다.
- P에서 권한과 `시작 알림 켜기`를 준비한 뒤 공식 콘솔을 D·36으로 전환하고 내비 화면을 유지합니다. OS가 앱 Activity를 가리는 현상과 서비스 기반 알림 경로를 구분합니다.

시험 전 해당 AVD의 네트워크 상태를 기록합니다. 호스트 Mac 전체나 다른 AVD의 네트워크를 끄지 않습니다.

```sh
"$MV_ADB" -s "$MV_SERIAL" shell settings get global wifi_on
"$MV_ADB" -s "$MV_SERIAL" shell settings get global mobile_data
"$MV_ADB" -s "$MV_SERIAL" shell dumpsys connectivity | rg 'Active default network:'
```

콘솔에서 D가 안정적으로 적용된 뒤 **해당 기기만** 차단합니다. 실제 기본 네트워크가 없음을 확인합니다.

```sh
"$MV_ADB" -s "$MV_SERIAL" shell svc wifi disable
"$MV_ADB" -s "$MV_SERIAL" shell svc data disable
"$MV_ADB" -s "$MV_SERIAL" shell dumpsys connectivity | rg 'Active default network:'
```

인터넷 차단은 로컬 Car/SDK 통신 및 콘솔의 DDS 연결과 구분합니다. 이 이미지에서는 차단 후 콘솔 제어가 끊기거나 재연결이 지연될 수 있습니다. 앱이 신뢰된 DRIVING을 확인하지 못하면 녹음을 시작하지 않고 원인을 확인합니다.

### 사용자 시작과 결과 확인

현재 일반 알림센터 진입이 미해결이므로, 개발 검수에서만 다음 명령으로 패널을 엽니다. 이 명령으로 열렸다고 이슈 #1을 닫지 않습니다.

```sh
"$MV_ADB" -s "$MV_SERIAL" shell cmd statusbar expand-notifications
"$MV_ADB" -s "$MV_CONSOLE_SERIAL" emu avd hostmicon
```

1. 사용자가 보라색 기억 저장소 아이콘의 준비 알림에서 **`녹음 시작`**을 누릅니다. 알림 본문은 녹음 시작 명령이 아닙니다.
2. 준비 상태 뒤 정확한 **`기억 저장소 · 메모 녹음 중`**이 나타나면 바로 짧은 문장을 말합니다. 우측 OS 마이크 아이콘만으로 제품 녹음 상태를 판정하지 않습니다.
3. 실제 녹음 종료·처리·이번 세션의 저장/실패 결과까지 기다립니다. 캡처는 최대 15초, 최종 결과 도착은 수락 후 최대 20초입니다. 이미 수락한 DB 저장이 끝나는 시각과는 다릅니다.
4. 중복 탭은 새 세션을 만들면 안 되며 취소 버튼은 작동해야 합니다. 취소·무음·실패 때 빈 원문을 새로 만들면 안 됩니다. 취소와 커밋 경합의 이미 저장된 메모까지 없다고 약속하지 않습니다.
5. 내비 Activity가 교체되지 않았고 실제 활성 녹음 알림이 종료됐는지 확인합니다. **남아 있는 이전 종료 알림을 이번 종료로 오인해 마이크를 먼저 끄지 않습니다.** 제품 녹음 서비스도 종료됐는지 확인한 뒤 입력을 끕니다.

```sh
"$MV_ADB" -s "$MV_SERIAL" shell dumpsys activity services app.memoryvault
"$MV_ADB" -s "$MV_CONSOLE_SERIAL" emu avd hostmicoff
```

서비스 dump는 이 앱의 상태 확인에만 쓰고 원문이나 광범위 시스템 로그를 수집하지 않습니다. 제품 서비스 종료는 OS의 상주 음성 기능 전체가 없다는 보장이 아닙니다.

## 7. 네트워크 복원·주차 표시·재실행

시험 전에 기록한 wifi/data 설정으로 되돌립니다. 원래 둘 다 켜져 있었던 경우에만 다음처럼 복원합니다.

```sh
"$MV_ADB" -s "$MV_SERIAL" shell svc wifi enable
"$MV_ADB" -s "$MV_SERIAL" shell svc data enable
"$MV_ADB" -s "$MV_SERIAL" shell dumpsys connectivity | rg 'Active default network:'
```

`wifi_on=1/mobile_data=1`만으로 실제 연결 복원을 선언하지 않습니다. 이 이미지에서는 설정 복원 뒤에도 기본 연결이 없을 수 있습니다. 그 경우 실제 transport로 **같은 AVD의 Android를 재부팅**하고, boot 완료·현재 사용자·실제 기본 연결을 다시 확인합니다. 기기를 초기화하거나 userdata를 삭제하지 않습니다.

```sh
"$MV_ADB" -s "$MV_SERIAL" reboot
# 연결이 돌아오면 devices -l과 boot 완료·현재 user를 다시 확인합니다.
"$MV_ADB" -s "$MV_SERIAL" shell getprop sys.boot_completed
"$MV_ADB" -s "$MV_SERIAL" shell dumpsys connectivity | rg 'Active default network:'
"$MV_ADB" -s "$MV_SERIAL" forward tcp:"$MV_CONSOLE_PORT" tcp:8088
```

재부팅 후 console forward와 브라우저 세션을 다시 확인하고 P·0으로 복귀합니다. 주행 기어·속도·bridge 준비가 실제 적용됐는지 확인하며 오래된 계기판 표시 하나만 믿지 않습니다.

1. 앱을 열어 `방문 미지정 음성 메모`에 이번 원문 1건이 `연결 분석 대기 · 음성 원문`으로 보이는지 확인합니다. 사용자가 발화와의 일치를 확인합니다. 최근 방문에 자동 확정되거나 수정 입력이 열리면 안 됩니다.
2. 이전에 저장·수정한 직접 메모와 방문 3곳도 남아 있어야 합니다.
3. 다음 명령으로 앱 프로세스를 종료·재실행하고 같은 저장 내용·중복 없는 방문을 확인합니다. 기록 중에 실행하지 않습니다.

```sh
"$MV_ADB" -s "$MV_SERIAL" shell am force-stop --user "$MV_USER_ID" app.memoryvault
"$MV_ADB" -s "$MV_SERIAL" shell am start -W --user "$MV_USER_ID" -n app.memoryvault/.MainActivity
```

기존 데이터가 있는 시험 기기에서는 전체 메모 수를 1로 가정하지 않습니다. 시험 전후 차이와 같은 세션의 중복 부재를 확인하고 기존 메모를 정리 명목으로 삭제하지 않습니다.

## 8. 안전 전환과 실패 범위

- **P 편집 중 D/UNKNOWN/TRANSITION:** 원문·dialog·IME가 숨겨지고 새 편집·권한 요청이 열리지 않아야 합니다. P 복귀만으로 편집창을 자동으로 다시 열지 않고 수동 이어 쓰기를 요구합니다.
- **수락된 녹음의 P↔D:** 상태가 유효하면 기존 제한 시간 내 세션을 유지합니다. 새 시작 조건은 DRIVING입니다.
- **UNKNOWN·권한/알림 상실·사용자 취소:** 새 시작 차단 및 진행 세션 취소를 확인합니다. 실제로 재현한 조건과 fake 기반 자동 검사만 수행한 조건을 나눠 기록합니다. 필수 권한을 시험 목적으로 바꿨다면 P에서 원래 상태로 복원합니다.
- **초기 차량 권한 거부:** 계속 UNKNOWN이고 입력·권한창이 자동 노출되지 않아야 합니다. 외부 설정의 최초 준비 경로와 구분합니다.
- **실제 플랫폼 단절:** 콘솔 창을 닫는 것으로 대체하지 않습니다. 시스템 Car/VHAL 서비스 kill·권한 우회는 승인된 기본 시험 절차가 아닙니다.

일반 사용자 알림 진입은 개발 패널 명령과 별도 수락 항목입니다. 지원되는 정상 진입을 확보한 뒤 P/D 조건별 버튼 접근과 내비 유지를 재검증하고 이슈 #1·인수인계를 갱신합니다.

## 9. 종료 체크와 인수 보고

- 제품 녹음 세션이 끝났고 활성 녹음 알림·서비스가 남지 않았는가?
- 호스트 마이크 입력이 off이고 AVD wifi/data·실제 기본 연결·공식 콘솔 P·0이 복원됐는가?
- 기존 데이터·다른 AVD·주행 규제를 보존했는가?
- 소스 커밋·APK 해시·기기와 자동 검사 수/실패, 실제로 확인한 동작 및 미검증 범위를 기록했는가?
- 3단계 차단 사항과 다음 첫 작업을 HANDOFF에 반영했는가?

원문은 사용자가 화면에서 확인하고, 보고에는 일치 확인 여부만 남깁니다. 키·서명 자격증명·사용자 원문·광범위 시스템 로그를 GitHub 이슈나 제출 자료에 올리지 않습니다. 사진·공유·실제 OpenAI는 현재 검사 대상 기능으로 표시하지 않습니다.
