# MV-02 플랫폼 실측 기록

검증일: 2026-11-13. 승인된 v4 로드맵의 임시 플랫폼 프로토타입이다. 제품 기능·저장소·방문 판정을 구현하지 않았다. 기존 AVD는 사용하지 않았다. 대상은 새 `Pleos_MemoryVault_API34`, `emulator-5580`, Android 사용자 10, 전용 ADB 서버 `tcp:localhost:5038`뿐이다.

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

```sh
export ADB_SERVER_SOCKET=tcp:localhost:5038
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 forward tcp:18088 tcp:8088
python3 spike/console_scenario.py parked --hold-seconds 30
```

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

## 원문 로그 보호

원본 SDK의 `SttCallback.onResponse(type,data)`는 data 문자열을 vendor Log로 전달했고, 결과 dispatch에도 vendor 로그가 있었다. `utils.Log`의 네 void 메서드 `a/b/c/d(String,String)`를 모두 bytecode로 읽었다. 역할은 인자 검사·로그 문자열 구성·Android Log 호출뿐이었다.

메인의 Ruling에 따라 debug 프로토타입에만 R8 `-assumenosideeffects`로 정확한 네 메서드 호출을 제거했다. 원본 AAR·음성 callback·인증 동작은 수정하지 않았다. [Android Additional rule types](https://developer.android.com/topic/performance/app-optimization/additional-rule-types)에서 이 규칙의 호출 제거 의미와 부작용 주의를 확인했다. `-dontshrink`, `-dontobfuscate`로 범위를 줄였다. AGP는 debuggable build의 최적화를 끄므로 이 임시 debug variant만 `isDebuggable=false`다. 따라서 debugger/run-as를 쓰는 개발 방식에는 맞지 않으며 이 설정을 제품 빌드 결론으로 재사용하지 않는다.

전체 APK의 3개 DEX에서 SDK class 109개를 검사했다. vendor Log 메서드 invoke는 0, 다른 SDK class로 인라인된 `android.util.Log` invoke도 0, 실제 `ResultListener.onUpdated` invoke는 1개다. SDK Log 클래스의 사용되지 않는 메서드 정의 자체는 dontshrink 때문에 남아 있다. 단순 DEX 문자열 검색을 통과 근거로 쓰지 않았다.

비개인 합성 sentinel을 실제 SDK의 SttCallback→SttViewModel→ResultListener로 전달해 callback `completed=false, match=true`를 관측했고 시스템 logcat에서 sentinel 일치 0을 확인했다. **이 합성 검사는 마이크나 음성 인식 성공 증거가 아니다.** 앱 밖 CaaS STT 서비스의 자체 로그까지 R8가 보호하는 것은 아니며 그 범위는 별도다.

메인의 별도 검사에서 이때 수집한 광범위 시스템 로그의 credential 할당형 의심 문자열이 탐지됐다. 메인이 분류한 발신은 별도 navigation 시스템 서비스이며 유효성은 확인하지 않았다. 값·로그라인 원문은 기록·인용하지 않는다. 메인은 해당 로그의 ignored 상태와 mode 0600 제한을 확인했다. 원본 local-only로 격리 보존하며 복사·전송·추가 원문 조회·삭제를 하지 않는다. 해당 시스템 로그는 외부 전달 대상이 아니다. sentinel 이후의 실제 발화 관측은 앱의 이벤트 메타데이터만 제한 수집했다. app-side SDK 로그 제거를 시스템 전체의 credential·로그 안전성 보장으로 확장하지 않는다.

첫 R8 실행은 선택적 preview tooling과 universal-access/Tink 전이 의존성의 미포함 클래스에서 실패했다. probe가 쓰지 않는 Compose preview runtime을 제외했다. Tink의 선택적 `KeysDownloader` HTTP 경로와 컴파일타임 annotation 관련 13개 타입만 정확히 `-dontwarn`으로 나열했다. package 단위 또는 전역 경고 무시는 없다. 해당 목록은 `app/src/debug/vendor-log-protection.pro`에 있다. 근거는 R8가 보고한 참조 위치, ON_DEVICE factory 분기, 앱 등록·서버 인증을 호출하지 않는 probe, 실제 오프라인 SDK 초기화 성공이다. 모든 인증 경로의 배포 적합성을 증명한 것은 아니며 이 임시 구성을 제품에 그대로 승격하지 않는다.

## STT 실측 상태

- 온라인/오프라인 모두 Pleos `initialize` onSuccess, mode ON_DEVICE, locale ko-KR 확인.
- Android API31+ `SpeechRecognizer.isOnDeviceRecognitionAvailable()`는 false였다. 따라서 별도의 Android 한국어 on-device engine 지원·실제 인식은 검증할 수 없으며 네트워크 engine으로 우회하지 않았다.
- 새 AVD의 원래 mobile_data=1, wifi_on=1을 기록하고 두 값만 잠시 끄자 `Active default network: none`이었다. Mac 네트워크나 다른 AVD는 변경하지 않았다. 실험 뒤 설정은 원래 1/1로 복원했다. 다만 재조회에서도 default network:none, GSM voice/data home, telephony dataConnectionState0이어서 실제 인터넷 재접속까지 복원됐다고 주장하지 않는다. 추가 무작정 toggle·라디오/privileged 설정 변경은 하지 않았다. 다음 새 AVD cold boot에서 실제 연결을 확인해야 한다.
- 사용자는 버튼을 직접 누르고 발화했다고 확인했다. agent 자동 녹음은 하지 않았다. 실제 request가 2회 관측됐다. 첫 요청은 RMS8회·final1회(chars1), 두 번째는 RMS38회(max23562.05)·partial9회·final1회(chars28)였다. 두 번째 request 06:15:24.666, ready25.210, ended29.109, final29.110, EPD0..96000, bounded collection 종료44.667, release44.669였다. STT_ERROR/INIT_FAILURE 없이 offline 마이크 신호·partial/final·해제를 확인했다. **사용자는 최종 화면이 말한 내용과 일치한다고 별도로 확인했다.** agent가 원문을 읽거나 저장해 문자열 비교한 결과는 아니다.
- 실제 SDK는 한 request 안에서 onStartedRecognition을 반복하고 onEndedRecognition 뒤에 final을 전달했다. ended에서 바로 listener를 해제하면 final이 유실될 수 있다. 제품은 request 단위 idempotent 처리와 bounded final 대기를 구현해야 한다.
- AudioService에서도 CaaS의 두 VOICE_RECOGNITION session rec stop을 확인했다. OS 자체 ambientai HOTWORD slot이 별도로 active/no config로 남아 있어 시스템 전체 녹음이 없다고 주장하지 않는다. 실제 음성 추가 유입을 막기 위해 새 AVD host microphone 입력만 hostmicoff로 종료했고 OK를 확인했다. 승인된 앱의 runtime grants와 최종 결과 UI는 유지했다. 추가 녹음은 하지 않았다.

## 재현·검수

[프로젝트 빌드 환경](../../README.md)을 설정한 뒤:

```sh
./gradlew --no-daemon --console=plain assembleDebug lintDebug testDebugUnitTest
python3 spike/verify_vendor_log_dex.py app/build/outputs/apk/debug/app-debug.apk \
  --dexdump "$ANDROID_HOME/build-tools/35.0.0/dexdump"
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 install --user 10 -r app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 shell am start -W -f 0x14000000 \
  --user 10 -n app.memoryvault/.spike.ParkedProbeActivity
```

`mv02_action` extra의 허용된 값은 `permissions`, `prepare_stt`, `start_stt`, `stop_stt`, `connect_car`, `disconnect_car`, `android_support`, `vendor_sentinel`이다. 테스트 앱이 foreground이고 사용자 발화 준비가 된 뒤에만 start_stt를 사용한다. `prepare_stt`로 onSuccess를 확인한 후 시작한다. 최대 20초 뒤 자원 해제가 기록돼야 한다. 원문은 일시 UI에만 표시하고 파일·진단에는 저장하지 않는다.

현재 전체 Gradle 검증은 실측 종료 후 재실행한 `build-handoff.log`, exit 0, `BUILD SUCCESSFUL in 9s`다. Unit test source가 없어 testDebugUnitTest는 NO-SOURCE이며 테스트 수를 만들어 기록하지 않는다. 승인된 throwaway prototype TDD 예외를 적용했다. Lint는 오류 0, 경고 20이다. 기존 버전/backup 경고 외에 임시 문자열, android.car 클래스 확인의 reflection 경고, 전이 JNA native library의 16KB 정렬 경고 3건이 포함된다. 이를 release 적합성 통과로 보지 않는다.

실행 APK `.local/evidence/mv-02-platform-probe.apk` SHA-256은 `a07809af119a875057163f57e8f83407a7cf3f439bea06fae6f736ffbe39cf11`이다. 빌드 입력 17개 해시 목록 `.local/evidence/mv-02/source-files.sha256`의 SHA-256은 `bbda1bc86c4d725fe0b2d750ab0d145b105e7a0bc2437744247ecc5eb11bd3df`이다. 계측 화면과 원문 없는 진단은 `.local/evidence/mv-02*`에만 있다. 이 디렉터리는 Git 전송 대상이 아니다. 소스·APK는 독립 검토가 끝날 때까지 보존하며 정리는 메인이 조율한다.

최종 source BASE는 `60af6c8ecb68a49b3b8c569e46f1489fa8f34a07`이다. 이 BASE에서 재검증한 `.local/evidence/mv-02-platform-probe-final-build.apk` SHA-256은 `f6aa1971c906a4e62eaa1feb015d287d35bd2ae879cb1198f1c5a30fba44c59d`이며 VCS metadata가 이 BASE를 가리킨다. 설치한 실측 APK와 모든 ZIP entry를 비교했고 `META-INF/version-control-info.textproto`만 달랐다. DEX·manifest·리소스·native library는 byte-identical이며 두 APK의 전체 DEX privacy audit를 각각 확인했다. 메인의 문서 경로 이력 정리는 앱 입력에 영향을 주지 않았다.

다시 사용자 음성으로 검수하려면 새 AVD의 `hostmicon`, 앱의 `prepare_stt` 성공 확인 뒤 사용자 직접 start가 필요하다. 임시 debug variant는 non-debuggable이므로 run-as/debugger를 요구하지 않는다. typed input이나 서버 인식으로 음성 검증을 대체하지 않는다.
