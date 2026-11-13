# MV-02 플랫폼 실측 기록

검증일: 2026-11-13. 승인된 v4 로드맵의 임시 플랫폼 프로토타입이다. 제품 기능·저장소·방문 판정을 구현하지 않았다. 기존 AVD는 사용하지 않았다. 대상은 새 `Pleos_MemoryVault_API34`, `emulator-5580`, Android 사용자 10, 전용 ADB 서버 `tcp:localhost:5038`뿐이다.

이 문서의 실측은 과거 프로토타입 체크포인트 **`175cc61`**에 해당한다. 독립 검토가 승인된 뒤 임시 debug 소스 4개·spike script 2개·STT 의존성·저장소·R8 설정을 현재 트리에서 제거했다. 현재 트리를 빌드하면 MV-01 부트스트랩이 나오며 아래 probe가 생성되지 않는다. 실측 APK·화면·원문 없는 이벤트 증거는 로컬에 보존했다. probe 재현은 문서 하단의 **별도 clone에서 `175cc61` 체크아웃** 절차만 사용한다.

## 현재 결론과 증거 범위

- 실제 `android.car.Car` 클래스 연결, 비동기 lifecycle `ready=true`, `CarUxRestrictionsManager` current 값 및 변경 callback, `CarPropertyManager`의 선택 기어와 속도 읽기·구독을 확인했다.
- P, D·정지, D·36 km/h, R을 공식 콘솔로 바꿨고 앱이 받는 값과 OS 차단 화면을 대조했다.
- 콘솔 세션이 종료돼 DDS가 inactive가 되어도 Car binder·property status·UX가 정상값으로 남는다. 이 환경에서 표준 Car API만으로 DDS 원천 단절을 확정할 수 있다는 주장은 하지 않는다.
- `distractionOptimized=true`인 임시 Driving Activity도 이 이미지에서는 OS 차단 화면에 가려졌다. DO 화면이 보인다는 전제로 제품을 구현하면 안 된다. 시스템 허용 목록·운전 제한 설정은 변경하지 않았다.
- Pleos STT 3.3.1의 `ON_DEVICE`, `ko-KR` 초기화와 사용자가 직접 시작한 실제 마이크 입력의 partial/final callback을 네트워크가 없는 새 AVD에서 확인했다. **문장 일치는 결과를 본 사용자의 별도 확인을 근거로 한다.** 원문을 추가 수집하거나 저장해 비교하지 않았다.

원본 부트스트랩 APK `.local/evidence/mv-01-bootstrap.apk`의 SHA-256 `f9c57c267ffa17d9c5bee3a377dbf0a0a39208582de168989682714810995d1d`를 다시 확인했고 보존했다.

## SDK와 실제 서명

[공식 import 안내](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/SpeechToText/importing-sdk)에 따라 공개 Maven `https://nexus-playground.pleos.ai/repository/maven-releases/`에서 `ai.pleos.playground:SpeechToText:3.3.1`을 실제 해석했다. 원본 AAR SHA-256은 `35109ad13400a7fa103cca9fabbf9b785e63a6a98b17a0698a1aef51458d37bf`이다. API 키, SDK 키, Client ID/Secret을 사용하지 않았다.

`javap`로 원본 AAR의 `classes.jar`를 확인했다. 생성자는 `SpeechToText(Context, Mode)`이고 enum은 `ai.pleos.playground.stt.constant.Mode.ON_DEVICE`이다. `initialize(OnInitializeListener)`, `setLocale(Locale.KOREA)`, `addListener(ResultListener)`, `request()`, `stop()`, `removeListener(ResultListener)`, `release()`가 실제 존재한다. `RecognitionMode`라는 enum은 없다.

ResultListener 실제 메서드:

```text
onReady()
onStartedRecognition()
onUpdated(String, boolean)
onUpdatedEpdData(long, long)
onUpdatedRms(double)
onEndedRecognition()
onError(ErrorType)
```

[초기화/해제](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/SpeechToText/initialization-and-release), [결과 listener](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/SpeechToText/result-listeners), [locale](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/SpeechToText/set-locale), [인식 시작·중지](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/SpeechToText/start-and-stop)와 대조했다. `completed=true`만 final이다. RMS는 dB로 표기하지 않았다. 성공 초기화 후 locale과 동일 listener를 등록한 뒤 요청한다. 인식은 최대 15초, 결과 대기는 시작으로부터 최대 20초이며 화면 이탈 때 stop/remove/release한다.

[공식 등록 안내](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/SpeechToText/app-registration)는 ON_DEVICE 전용 사용에 앱 등록이 필요 없다고 명시한다. SERVER/HYBRID·일반 네트워크 SpeechRecognizer fallback은 사용하지 않았다.

## 실제 권한

| 권한 | 새 AVD의 protectionLevel / 실제 결과 |
| --- | --- |
| `pleos.car.permission.STT_SERVICE` | normal, 설치 후 granted |
| `android.car.permission.CAR_POWERTRAIN` | normal, 설치 후 granted |
| `android.car.permission.CAR_SPEED` | dangerous, Android UI에서 LOCATION 그룹의 ‘위치 정보’ 허용 요청으로 표시됨 |
| `android.permission.RECORD_AUDIO` | dangerous, ‘앱 사용 중에만 허용’ 요청 |

앱 manifest에 FINE/COARSE_LOCATION을 추가하지 않았다. 속도 미허용 때 config 조회가 null이고 read가 SecurityException이었다. 이를 차량 속도 미지원으로 단정하지 않았다. 사용자가 새 테스트 AVD의 두 런타임 권한을 명시 승인한 후 UI에서만 허용했다. 사용자 10의 CAR_SPEED/RECORD_AUDIO granted=true를 확인했다. 사용자 0은 앱이 설치되지 않았고 두 권한을 허용하지 않았다.

처음 권한 UI의 허용 버튼 조작은 자동 승인 검토에서 ‘명시적인 마이크 접근 권한 승인 없음’으로 거절됐다. 우회하지 않았고 메인이 사용자에게 정확한 두 권한·대상·로컬 STT 목적을 설명한 뒤 새 승인을 받았다. 그 승인 후 해당 UI만 처리했다.

## 공식 콘솔과 차량 신호

[공식 가상 콘솔 안내](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/use-vehicle-emulator)에 따라 비어 있는 로컬 18088을 새 AVD 8088에만 연결했다. 브라우저 CUA는 `browser access host is unavailable`로 사용할 수 없었고 종료 summary를 전송했다. 대신 새 AVD에서 내려받은 실제 콘솔 HTML의 HTTP handler를 그대로 사용했다.

18088/5580/5038은 당시 실측 식별자이며 재현 명령의 기본값으로 재사용하지 않는다. 기존 forward를 교체하지 말고 아래 별도 clone 절차의 새 serial·서버·콘솔 포트를 사용한다.

사용한 handler는 `POST /browser/session?client=...`, `PUT /vehicle/controls`, `PUT /vehicle/gear`, `GET /vehicle/state`다. 콘솔 UI는 1초마다 session heartbeat를 보낸다. heartbeat가 없으면 `bridge_ready=false`, `emulator_active=false`, DDS 모든 항목 false였다. 실제 heartbeat를 보내자 준비 상태가 true가 되고 입력을 전달했다. 실험 script는 이 동작을 재현하고 종료 때 해당 session만 닫는다. 콘솔 HTTP 상태는 시험 도구의 상태이며 제품의 차량 안전 신호로 사용하지 않는다.

| 공식 콘솔 상태 | 앱 GEAR_SELECTION | 앱 PERF_VEHICLE_SPEED | 앱 UX current/callback | OS 화면 |
| --- | --- | --- | --- | --- |
| P / 0 km/h | 4, status AVAILABLE | 0.0 m/s, AVAILABLE | requiresDO=false, mask 0 | non-DO 진단 화면 표시 |
| D / 0 km/h | 8, AVAILABLE | 0.0 m/s, AVAILABLE | requiresDO=true, mask 80 (`0x50`) | non-DO 차단 overlay |
| D / 36 km/h | 8, AVAILABLE | 10.0 m/s, AVAILABLE | requiresDO=true, mask 80 | non-DO 차단 overlay |
| R / 0 km/h | 2, AVAILABLE | 0.0 m/s, AVAILABLE | requiresDO=true, mask 80 | 차단 overlay |

앱은 `GEAR_SELECTION=289408000`을 읽었다. `CURRENT_GEAR`를 대신 사용하지 않았다. 속도 property는 `291504647`, area는 0이다. [Android VehiclePropertyIds](https://developer.android.com/reference/android/car/VehiclePropertyIds#GEAR_SELECTION)의 의미·권한을 원본 SDK stub와 대조했다. 이 표는 해당 새 AVD의 관측값이며 다른 지역·양산 차량의 UX mask까지 일반화하지 않는다. OS의 `CarUxRestrictionsManagerService` dump도 parked mask 0, idling/moving mask `0x50`, unknown mask `0x1ff`로 표시했다.

## 단절과 Activity의 한계

P·0 상태에서 콘솔 session을 닫고 `bridge_ready=false`, `emulator_active=false`, DDS 모두 false를 확인했다. 그 뒤 앱의 Car를 다시 연결해 읽어도 lifecycle ready=true, 기어 4/status 0, 속도 0/status 0, UX false/mask 0이었다. 기어 timestamp는 유지됐고 속도 timestamp는 계속 갱신됐다. 원천 DDS가 끊겼다는 `ready=false`, unavailable/error property, UX unknown callback은 관측하지 못했다. 따라서 binder ready 또는 신선한 속도 timestamp만으로 원천 신호의 건전성을 입증할 수 없다.

앱 자신의 `Car.disconnect()`를 실행한 경로에서는 listener·property callback을 해제하고 UI 상태를 UNKNOWN으로 기록했다. 이는 앱의 local disconnect 확인이며 Car 시스템 서비스 사망이나 실제 차량 연결 상실의 검증을 대신하지 않는다. 서비스 재시작·kill, privileged settings, 운전 제한 강제 해제는 하지 않았다. `cmd car_service help`도 user build의 SecurityException으로 차단돼 그대로 중단했다.

두 debug Activity는 non-DO와 `distractionOptimized=true` DO로 나눴다. 최종 APK manifest의 boolean metadata도 aapt2로 확인했다. D/R에서 non-DO 화면은 OS overlay에 가려졌으며, task의 resumed Activity가 여전히 앱으로 표시되고 callback이 계속 오는 경우가 있었다. **제품에서 onStop만 기다려 저장·녹음·화면을 제한하면 부족하다.** UX callback 자체를 받아 즉시 제한해야 한다.

DO Activity를 같은 task에서 열기, Back, NEW_TASK|CLEAR_TASK로 별도 root로 열기를 시험했지만 DO 화면도 OS overlay에 가려졌다. 이 이미지의 원인을 category/허용 목록/캐시 중 하나로 단정하지 않는다. 공개 DO 선언만으로 이 이미지의 overlay를 없앨 수 있다고 보고하지 않는다. OS가 가리는 동안 앱 자체 limited 화면 표시를 보장할 수 없으므로, 안전한 OS 차단 결과를 수용하는 제품 흐름은 메인이 판단해야 한다.

메인의 후속 진단 `task2-do-followup.json`은 system build `user`, installer `null`, 기존 package-policy dump에 앱 없음, 해당 앱의 설치 출처 거절 경고 4회·allowlist 추가 0회를 기록했다. 관측된 경고와 일치하는 문구는 AOSP의 `not installed from permitted sources`이다. [AOSP CarPackageManagerService](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/master/service/src/com/android/car/pm/CarPackageManagerService.java)의 비-system 앱 검사에는 디버그 시스템 빌드가 아닐 때 허용된 installer인지 확인하고, installer가 null/비허용이면 DO metadata 처리 전에 거절하는 분기가 있다. **이 자료와 관측이 설치 정책 원인 가설을 지지하지만, 해당 Pleos 이미지의 vendor 구현을 완전히 입증한 것은 아니다.** 정책·installer·allowlist는 변경하지 않았다. 이 규칙의 debug build는 시스템 빌드의 구분이며 APK의 debuggable 플래그와 혼동하지 않는다.

[Pleos 주행 규제 개발 가이드](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide)는 DO 선언 앱도 CarUxRestrictions/ActiveRestrictions에 따라 기능 제한을 직접 적용하도록 요구한다. 주차 중 오프라인 한국어 STT 성공은 확인했지만 **필수 F2 주행 음성 경로와 DDS 원천 단절 시 UNKNOWN 계약은 미해결**이다. OS overlay를 받아들이는 것만으로 이 필수 조건을 통과시키거나 typed input으로 대체하지 않는다. 이후 구현 전에 메인이 확인 가능한 플랫폼 경로와 승인 경계를 판단해야 한다.

## 원문 로그 보호

원본 SDK의 `SttCallback.onResponse(type,data)`는 data 문자열을 vendor Log로 전달했고, 결과 dispatch에도 vendor 로그가 있었다. `utils.Log`의 네 void 메서드 `a/b/c/d(String,String)`를 모두 bytecode로 읽었다. 역할은 인자 검사·로그 문자열 구성·Android Log 호출뿐이었다.

메인의 Ruling에 따라 debug 프로토타입에만 R8 `-assumenosideeffects`로 정확한 네 메서드 호출을 제거했다. 원본 AAR·음성 callback·인증 동작은 수정하지 않았다. [Android Additional rule types](https://developer.android.com/topic/performance/app-optimization/additional-rule-types)에서 이 규칙의 호출 제거 의미와 부작용 주의를 확인했다. `-dontshrink`, `-dontobfuscate`로 범위를 줄였다. AGP는 debuggable build의 최적화를 끄므로 이 임시 debug variant만 `isDebuggable=false`다. 따라서 debugger/run-as를 쓰는 개발 방식에는 맞지 않으며 이 설정을 제품 빌드 결론으로 재사용하지 않는다.

전체 APK의 3개 DEX에서 SDK class 109개를 검사했다. vendor Log 메서드 invoke는 0, 다른 SDK class로 인라인된 `android.util.Log` invoke도 0, 실제 `ResultListener.onUpdated` invoke는 1개다. SDK Log 클래스의 사용되지 않는 메서드 정의 자체는 dontshrink 때문에 남아 있다. 단순 DEX 문자열 검색을 통과 근거로 쓰지 않았다.

비개인 합성 sentinel을 실제 SDK의 SttCallback→SttViewModel→ResultListener로 전달해 callback `completed=false, match=true`를 관측했고 시스템 logcat에서 sentinel 일치 0을 확인했다. **이 합성 검사는 마이크나 음성 인식 성공 증거가 아니다.** 앱 밖 CaaS STT 서비스의 자체 로그까지 R8가 보호하는 것은 아니며 그 범위는 별도다.

메인의 별도 검사에서 이때 수집한 광범위 시스템 로그의 credential 할당형 의심 문자열이 탐지됐다. 메인이 분류한 발신은 별도 navigation 시스템 서비스이며 유효성은 확인하지 않았다. 값·로그라인 원문은 기록·인용하지 않는다. 메인은 해당 로그의 ignored 상태와 mode 0600 제한을 확인했다. 원본 local-only로 격리 보존하며 복사·전송·추가 원문 조회·삭제를 하지 않는다. 해당 시스템 로그는 외부 전달 대상이 아니다. sentinel 이후의 실제 발화 관측은 앱의 이벤트 메타데이터만 제한 수집했다. app-side SDK 로그 제거를 시스템 전체의 credential·로그 안전성 보장으로 확장하지 않는다.

첫 R8 실행은 선택적 preview tooling과 universal-access/Tink 전이 의존성의 미포함 클래스에서 실패했다. probe가 쓰지 않는 Compose preview runtime을 제외했다. Tink의 선택적 `KeysDownloader` HTTP 경로와 컴파일타임 annotation 관련 13개 타입만 정확히 `-dontwarn`으로 나열했다. package 단위 또는 전역 경고 무시는 없다. 해당 목록은 과거 `175cc61`의 `app/src/debug/vendor-log-protection.pro`에 보존돼 있다. 근거는 R8가 보고한 참조 위치, ON_DEVICE factory 분기, 앱 등록·서버 인증을 호출하지 않는 probe, 실제 오프라인 SDK 초기화 성공이다. 모든 인증 경로의 배포 적합성을 증명한 것은 아니며 이 임시 구성을 제품에 그대로 승격하지 않는다. 현재 트리에서는 해당 설정과 SDK를 제거했다.

## STT 실측 상태

- 온라인/오프라인 모두 Pleos `initialize` onSuccess, mode ON_DEVICE, locale ko-KR 확인.
- Android API31+ `SpeechRecognizer.isOnDeviceRecognitionAvailable()`는 false였다. 따라서 별도의 Android 한국어 on-device engine 지원·실제 인식은 검증할 수 없으며 네트워크 engine으로 우회하지 않았다.
- 새 AVD의 원래 mobile_data=1, wifi_on=1을 기록하고 두 값만 잠시 끄자 `Active default network: none`이었다. Mac 네트워크나 다른 AVD는 변경하지 않았다. 실험 뒤 설정은 원래 1/1로 복원했다. 다만 재조회에서도 default network:none, GSM voice/data home, telephony dataConnectionState0이어서 실제 인터넷 재접속까지 복원됐다고 주장하지 않는다. 추가 무작정 toggle·라디오/privileged 설정 변경은 하지 않았다. 다음 새 AVD cold boot에서 실제 연결을 확인해야 한다.
- 사용자는 버튼을 직접 누르고 발화했다고 확인했다. agent 자동 녹음은 하지 않았다. 실제 request가 2회 관측됐다. 첫 요청은 RMS8회·final1회(chars1), 두 번째는 RMS38회(max23562.05)·partial9회·final1회(chars28)였다. 두 번째 request 06:15:24.666, ready25.210, ended29.109, final29.110, EPD0..96000, bounded collection 종료44.667, release44.669였다. STT_ERROR/INIT_FAILURE 없이 offline 마이크 신호·partial/final·해제를 확인했다. **사용자는 최종 화면이 말한 내용과 일치한다고 별도로 확인했다.** agent가 원문을 읽거나 저장해 문자열 비교한 결과는 아니다.
- 실제 SDK는 한 request 안에서 onStartedRecognition을 반복하고 onEndedRecognition 뒤에 final을 전달했다. ended에서 바로 listener를 해제하면 final이 유실될 수 있다. 제품은 request 단위 idempotent 처리와 bounded final 대기를 구현해야 한다.
- AudioService에서도 CaaS의 두 VOICE_RECOGNITION session rec stop을 확인했다. OS 자체 ambientai HOTWORD slot이 별도로 active/no config로 남아 있어 시스템 전체 녹음이 없다고 주장하지 않는다. 실제 음성 추가 유입을 막기 위해 새 AVD host microphone 입력만 hostmicoff로 종료했고 OK를 확인했다. 승인된 앱의 runtime grants와 최종 결과 UI는 유지했다. 추가 녹음은 하지 않았다.

## 과거 프로토타입 재현 — 별도 clone의 175cc61만 사용

아래는 재현용 절차이며 정리 과정에서 다시 clone·AVD 생성·설치·녹음을 실행한 것은 아니다. **현재 작업 폴더에서 checkout하거나 기존 AVD에 재설치하지 않는다.** 로컬 저장소의 과거 체크포인트를 새 임시 폴더에 clone한다. 이 체크포인트가 원격에 있다고 가정하지 않는다. 새 clone에는 `.local/`의 기존 키·로그·AVD·사용자 데이터가 복사되지 않는다.

```sh
# 시작 위치는 현재 Pleos 저장소 루트다. 새 clone에만 checkout한다.
MV02_SOURCE_REPO="$PWD"
MV02_REPRO_DIR="$(mktemp -d "${TMPDIR:-/tmp}/pleos-mv02-repro.XXXXXX")"
git clone --no-hardlinks --no-checkout "$MV02_SOURCE_REPO" "$MV02_REPRO_DIR"
git -C "$MV02_REPRO_DIR" checkout --detach 175cc61
cd "$MV02_REPRO_DIR"
git rev-parse --short HEAD
pwd
```

HEAD 출력이 `175cc61`인지 확인한다. **이후 모든 명령은 이 새 clone 안에서만 실행한다.** 체크포인트 README의 JDK17/SDK35 사전 조건과 환경 설정을 사용한다. macOS 예시는 다음과 같고 JDK/SDK 설치 위치가 다르면 실제 경로로 설정한다.

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_USER_HOME="$PWD/.local/android"
export GRADLE_USER_HOME="$PWD/.local/gradle-home"
export ANDROID_EMULATOR_HOME="$PWD/.local/android"
export ANDROID_AVD_HOME="$PWD/.local/avd"
mkdir -p "$ANDROID_USER_HOME" "$GRADLE_USER_HOME" "$ANDROID_AVD_HOME"
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./gradlew --no-daemon --console=plain assembleDebug lintDebug testDebugUnitTest
python3 spike/verify_vendor_log_dex.py app/build/outputs/apk/debug/app-debug.apk \
  --dexdump "$ANDROID_HOME/build-tools/35.0.0/dexdump"
```

새 debug 서명키가 생성되므로 APK 해시가 보존된 실측 APK와 달라도 된다. 기존 debug key를 복사하거나 서명 충돌 해결을 위해 기존 앱을 uninstall하지 않는다. 새 AVD만 사용하면 기존 설치와 충돌하지 않는다.

아래 이름과 포트는 재현 전용 예시다. **ADB 5040, emulator 5584/5585, 콘솔 18090이 다른 프로세스에 사용되지 않는지 먼저 확인**하고, 사용 중이면 비어 있는 값으로 정해 모든 명령과 URL을 함께 바꾼다. 실측 기기 5580/ADB5038·다른 사용자의 기존 기기를 선택하지 않는다. [이미지 준비 기록](pleos-memory-vault-v4.md)의 공식 API34 Pleos image와 기기 프로필이 SDK에 설치돼 있어야 한다.

```sh
export MV02_AVD_NAME=Pleos_MV02_Repro_API34
export MV02_SERIAL=emulator-5584
export ADB_SERVER_SOCKET=tcp:localhost:5040
printf 'no\n' | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd \
  --name "$MV02_AVD_NAME" \
  --package 'system-images;android-34;pleos_car_emulator;arm64-v8a' \
  --device Pleos_Connect_v2_0 \
  --path "$ANDROID_AVD_HOME/$MV02_AVD_NAME.avd"
python3 - <<'PY'
import os
from pathlib import Path
p = Path(os.environ['ANDROID_AVD_HOME']) / (os.environ['MV02_AVD_NAME'] + '.avd/config.ini')
values = dict(line.split('=', 1) for line in p.read_text().splitlines() if '=' in line)
values.update({'hw.ramSize': '4096', 'hw.audioInput': 'yes', 'hw.keyboard': 'yes',
               'fastboot.forceColdBoot': 'yes', 'fastboot.forceFastBoot': 'no',
               'fastboot.forceChosenSnapshotBoot': 'no'})
p.write_text(''.join(f'{key}={value}\n' for key, value in values.items()))
PY
"$ANDROID_HOME/emulator/emulator" -avd "$MV02_AVD_NAME" \
  -port 5584 -memory 4096 -no-snapshot -no-boot-anim -no-metrics
```

emulator 명령은 실행 상태로 둔다. 두 번째 터미널에서 같은 **새 clone 경로**로 이동하고 위 export를 동일하게 설정한다. `sys.boot_completed=1`, AVD 이름이 새 이름인지 확인한 뒤 현재 사용자 번호를 조회한다.

```sh
"$ANDROID_HOME/platform-tools/adb" -s "$MV02_SERIAL" wait-for-device
"$ANDROID_HOME/platform-tools/adb" -s "$MV02_SERIAL" emu avd name
"$ANDROID_HOME/platform-tools/adb" -s "$MV02_SERIAL" shell getprop sys.boot_completed
MV02_USER_ID="$("$ANDROID_HOME/platform-tools/adb" -s "$MV02_SERIAL" shell am get-current-user | tr -d '\r')"
"$ANDROID_HOME/platform-tools/adb" -s "$MV02_SERIAL" shell pm list packages --user "$MV02_USER_ID" app.memoryvault
```

마지막 명령에 `app.memoryvault`가 이미 보이거나 AVD 이름이 다르면 설치를 멈추고 별도의 새 AVD를 선택한다. 기존 설치를 삭제하거나 `-r`로 덮어쓰지 않는다. 새 기기에만 다음을 실행한다.

```sh
"$ANDROID_HOME/platform-tools/adb" -s "$MV02_SERIAL" install --user "$MV02_USER_ID" app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/platform-tools/adb" -s "$MV02_SERIAL" shell am start -W -f 0x14000000 \
  --user "$MV02_USER_ID" -n app.memoryvault/.spike.ParkedProbeActivity
"$ANDROID_HOME/platform-tools/adb" -s "$MV02_SERIAL" forward tcp:18090 tcp:8088
```

과거 `console_scenario.py`의 BASE는 18088 고정이므로 그대로 실행하면 다른 기기를 향할 수 있다. **새 clone에서만** URL 한 줄을 새 forward와 일치시키고 사용한다. 앱 소스와 설정은 체크포인트 그대로다.

```sh
python3 - <<'PY'
from pathlib import Path
p = Path('spike/console_scenario.py')
old = 'BASE = "http://127.0.0.1:18088"'
assert p.read_text().count(old) == 1
p.write_text(p.read_text().replace(old, 'BASE = "http://127.0.0.1:18090"'))
PY
python3 spike/console_scenario.py parked --hold-seconds 30
# 같은 새 clone에서 idling / moving / reverse를 각각 실행해 표의 값을 비교한다.
```

권한은 새 앱 UI에서 CAR_SPEED·RECORD_AUDIO만 목적을 확인하고 허용한다. 실제 발화 검수 때만 새 기기의 hostmicon을 켠다. 로컬 STT 준비 onSuccess 뒤 사용자가 직접 15초 버튼을 누르며, 화면 원문이 맞는지는 사용자가 확인한다. 오프라인 실험은 **이 새 serial의** data/wifi 원래 값을 기록한 뒤 해당 기기에서만 끄고 default network none을 확인한다. 끝나면 원래 설정과 hostmicoff를 복원하고 실제 재접속 여부도 별도로 확인한다. 전역 Mac 네트워크·다른 AVD·운전 제한 정책·installer는 변경하지 않는다. 원문 없는 MV02 이벤트만 관측하며 광범위 system log는 수집하지 않는다.

`mv02_action` extra는 과거 checkpoint에서만 `permissions`, `prepare_stt`, `start_stt`, `stop_stt`, `connect_car`, `disconnect_car`, `android_support`, `vendor_sentinel`을 받는다. 최대 20초 뒤 자원 해제를 확인한다. 임시 debug variant는 non-debuggable이므로 run-as/debugger를 요구하지 않는다. typed input이나 서버 인식으로 필수 음성 검증을 대체하지 않는다.

## 보존된 프로토타입 검증과 현재 트리의 구분

`175cc61` 프로토타입 검증의 `build-handoff.log`는 exit 0/9초, Lint 오류 0/경고 20, testDebugUnitTest NO-SOURCE였다. 독립 검토는 Critical/Important 없이 임시 실험 체크포인트를 승인했다. M1의 추가 경고 8개는 reflection 1·진단 문자열 4·전이 JNA 16KB 정렬 3개였다. 임시 소스·SDK를 제거한 현재 트리에서는 이 원인이 사라지지만, **후속 제품 SDK 통합에서 JNA/native 16KB 정렬과 SDK 원문 로그 보호를 다시 해결해야 한다.** M2인 SDK XML v3/v4 도구 해석 경고는 환경 문제로 별도 재검토하며 검사를 끄거나 SDK를 임의 변경하지 않았다.

실행 APK `.local/evidence/mv-02-platform-probe.apk` SHA-256은 `a07809af119a875057163f57e8f83407a7cf3f439bea06fae6f736ffbe39cf11`이다. 실험 source 입력 17개 해시 목록의 SHA-256은 `bbda1bc86c4d725fe0b2d750ab0d145b105e7a0bc2437744247ecc5eb11bd3df`이다. 이력 정리 후 BASE `60af6c8`에서 만든 보존 APK `.local/evidence/mv-02-platform-probe-final-build.apk`는 `f6aa1971c906a4e62eaa1feb015d287d35bd2ae879cb1198f1c5a30fba44c59d`였다. 두 APK의 모든 ZIP entry를 비교했고 VCS metadata만 달랐으며 전체 DEX 검사는 각각 확인했다. 이 해시는 **현재 트리의 부트스트랩 APK 해시가 아니다.**

정리 후 현재 트리 검증은 아래 정리 결과와 로컬 cleanup-build.log를 기준으로 한다. 현재 트리에서 probe 명령을 실행하지 않는다. 과거 checkpoint source와 실측 증거는 보존하며 plan workspace·격리 로그·원본 문서는 이 정리에서 삭제하지 않는다.

## 정리 후 현재 트리 검증

독립 검토 승인 후 체크포인트 `175cc61`을 남기고 task-owned debug 4파일·spike 2파일을 삭제했다. `app/build.gradle.kts`와 `settings.gradle.kts`는 임시 추가분만 되돌렸고, 두 파일이 `60af6c8` baseline과 byte-identical임을 비교했다. `app/src/main`은 `175cc61`과 차이가 없다. 현재 트리는 부트스트랩과 이 영구 findings를 유지하며, 기기에 설치돼 있던 과거 probe는 이 소스 정리에서 설치·삭제·변경하지 않았다.

정리 후 전체 명령 `./gradlew --no-daemon --console=plain assembleDebug lintDebug testDebugUnitTest`는 exit 0, 22초, 52 tasks였다. `testDebugUnitTest`는 NO-SOURCE다. Lint는 오류 0/기존 경고 12로 돌아왔다: OldTargetApi 1, AndroidGradlePluginVersion 1, GradleDependency 8, NewerVersionAvailable 1, DataExtractionRules 1. M1의 추가 8개 경고는 프로토타입/SDK 제거로 현재 빌드에서 사라졌지만 SDK/JNA의 제품 도입 문제 자체를 수정한 것은 아니다. M2 SDK XML v3/v4 해석 경고는 이번 전체 명령에서도 관측됐고 별도 tooling 검토 과제로 남긴다.

별도 dependency task가 exit 0으로 끝났고 실제 debugRuntimeClasspath에 SpeechToText·universal-access·JNA가 없음을 확인했다. 새 APK의 manifest에서 RECORD_AUDIO/STT_SERVICE/CAR_SPEED/CAR_POWERTRAIN·probe Activity·DO metadata가 없고, 4개 DEX에 STT/probe class definition이 없으며 UA/JNA native library도 없었다. 기존 `android.car` optional uses-library와 main 화면은 보존됐다.

현재 부트스트랩 APK의 보존 사본은 `.local/evidence/mv-02-bootstrap-after-cleanup.apk`, SHA-256 `e5f0e84e856b65dcf91a9ae2df3b789d13f33ce75290e9942c742f7b5d2e2e02`다. MV-01 보존 APK와 모든 ZIP entry의 압축 해제 내용을 직접 비교해 추가/제거/변경 0을 확인했다. archive 전체 SHA 차이를 실행 내용 차이로 해석하지 않는다. 근거는 로컬 `cleanup-build.log`, `cleanup-dependencies.log`, `cleanup-manifest.txt`, `cleanup-apk-audit.json`, `cleanup-bootstrap-entry-comparison.json`에 있다. 기존 실측 APK 3개의 해시도 그대로였다. 현재 트리 APK 실행은 README의 MainActivity 부트스트랩 절차를 따르며 probe 재현 절차와 섞지 않는다.
