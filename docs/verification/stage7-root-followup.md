# Stage7 root 직접 후속 검증 — 2026-11-15

사용자가 V 인계 뒤 UI4/실내비/추가 실API를 root가 직접 검사하도록 명시했다. 이번 범위는 이전 V 전담 규칙의 사용자 예외이며 새 검증 작업자는 사용하지 않았다. 제품 코드는 바꾸지 않고 원 실패·V 결과를 보존했다.

## 실제 AI 연결

기존 사진 설명은 주황색 배경/파란 가로 영역/노란 원이었다. 과거 '바다 위 해' 입력의 REVIEW는 이 도형 설명만으로 경험을 확정할 수 없었던 보수 판단과 일치하지만 모델 내부 이유까지 관측한 것은 아니다. 이번에는 고정된 두 합성 시나리오만 추가했고 prompt/model/effort/tier를 바꾸거나 같은 실패 입력을 성공할 때까지 반복하지 않았다.

- 실제 이미지 API가 앞서 생성한 설명과 일치하는 오늘의 그림 기억 → SUGGEST, PHOTO1개, 유효 CP 범위.
- 같은 날 같은 해변에서 빨간 연을 날렸다는 다른 원문 NOTE → SUGGEST, NOTE1개, 유효 CP 범위.
- 각 후보의 실제 기록 날짜를10월9일로 옮기고 새 원문은11월1일 오늘로 유지 → 두 경우 모두 NO_MATCH, 추가 모델 호출0.
- 동일 요청 replay도 추가 호출0. 총 추가 inference2회이며 기존 누적 장부/불명확 예약/촬영 잔여를 유지했다. 개인 사진·음성은 업로드하지 않았다. 원시 결과와 금액은 ignored local-only.

이로써 실모델 SUGGEST 성공 사례 공백은 닫는다. 무제한 자연어/모든 사진의 연결 정확도를 뜻하지 않는다.

## 기존 UI4

기존 assertion/제품코드/시험 본문은 변경하지 않았다. bounded scalar lifecycle listener를 추가해 네 실패메서드와 내비 상태 관측을 한 회차 실행했다(8.011초). 세 UI는 PASS, noIncludedVisitsDisablesPreview만 NoRoot FAIL, 내비 상태 관측은 PASS였다. lifecycle에는 Activity pause/stop/start/resume가 있으며 failure listener는 teardown 뒤 실행될 수 있어 최초 실패 순간 상태라고 단정하지 않는다.

유일하게 남은 메서드를 앞선 UI 입력/화면 전환과 분리한 control로 한 번 실행해 PASS(2.972초)를 확인했다. 네 기능 경계는 이번 scoped/control에서 각각 assertion을 통과했지만 한 회차4/4 GREEN이 아니며 연속 실행에서의 NoRoot 재발과 원래146중4FAIL을 지우지 않는다. 특정 순서/OEM lifecycle 원인의 인과는 미확정이고 재시도 루프나 제품 수정으로 해결했다고 주장하지 않는다.

## 실제 내비

기존 Application provider를 추가 getter 없이 관측해 UNAVAILABLE/rows0을 status bundle로 확보했다. 정상 권한 NAVI_ROUTE granted=true, 설치된 NaviService3.0.4/versionCode55와 서비스 존재를 읽기 확인했다. 실제 내비 앱 정상 진입 화면에는 '서비스 연결 중입니다.'가 표시됐다.

원인을 구분하는 수동 진단1회에서 기존 provider를 닫고 한 개 SDK 연결의 callback 종류만 관측했다. CONNECTED → DESTINATION_REQUESTED → RECENT_REQUESTED가 확인됐지만 이후6초 안 destination/recent/status/error callback은 없었다. 관측 테스트 PASS는 연결 callback/진단 수행 성공이지 목적지 조회 성공이 아니다. 제품의5초 응답 timeout 후 UNAVAILABLE와 일치한다. 서비스 바인딩은 확인됐고 목적지 데이터 수신은 환경에서 아직 확인되지 않았다. SDK 내부 등록/제공자 준비/지도 서비스 문제 중 어느 것인지 근본 원인은 미확정이다. 경로 설정·취소·권한·기본앱·재부팅·사용자 데이터 변경은 하지 않았다.

## 식별·근거·종료

- 제품 APK SHA256: 0c068687bf5651c30d70c5f5385a36375f18337712d698544827490ac3efcb8b (V 제품과 동일).
- 최종 진단 시험 APK SHA256: f2051b1eb3ab44a605f579dd34db84e672a3e0efeeeb0b0dd62fca12de419e73. 시험 APK만 동일 서명 install-r, 제품 데이터 보존.
- 추가 시험 소스: Stage7LifecycleObserver.kt, Stage7NavigationObservationTest.kt, Stage7NavigationTransportProbeTest.kt. 마지막 probe는 Application provider를 닫는 수동 진단용이므로 일반 제품 수락 시험으로 해석하거나 자동 suite에 무작정 합산하지 않는다.
- 로컬 근거 .local/verification/stage7/: root-followup-test-build-01.log, root-ui4-navigation-01.log, root-ui-empty-isolated-control.log, root-nav-diagnostic-build.log, root-navigation-transport-01.log. 별도 local-only root-live-followup-result.json 및 harness. UI/system/SDK 기록은 개인 payload 없이 enum/개수/수명주기만 사용했다.
- 최종 기기 user10/MainActivity 정상 진입으로 복귀, 마이크/경로/권한 변경 없음. 새 서버/reverse를 생성하지 않았고 유료 호출은 종료했다.
- root 직접 확인 결과: 실AI 제안 성공 확인, UI4 기능 경계 scoped 확인/NoRoot 간헐 실패 유지, 내비 바인딩 확인/실제 데이터 응답 미확인. main 병합·push·Stage8·전체GREEN 선언 없음.

## 공식 NaviHelper 문서 대조 및 DNS 원인 범위 — 2026-11-15 후속

사용자가 지정한 공식 NaviHelper 소개·초기화·권한·Virtual Console 문서를 직접 확인했다. SDK3.0.5, initialize(onConnected) 후 listener 등록 및 조회, NAVI_ROUTE 권한은 현재 구현과 일치한다. 공식 소개는 실제 이벤트 전달이 Navigation 앱 구현과 현재 상태에 의존함을 명시한다.

같은 제품/시험 APK에서 읽기 전용 SDK 진단을 한 번 재시도했다. 6.063초, CONNECTED/DESTINATION_REQUESTED/RECENT_REQUESTED만 관측됐고 데이터 callback은 없었다. JUnit OK1은 연결 진단 성공일 뿐 목적지 조회 수락이 아니다.

새 증거: Navi42가 내비 gateway에 대해 UnknownHostException을 반복한다. Mac에서는 해당 gateway 및 document.pleos.ai가 DNS 조회되지만 전용 에뮬레이터에서는 두 주소 모두 unknown host였다. 비행기 모드0, 기본 eth0 연결과 DNS10.0.2.3은 존재한다. 따라서 에뮬레이터의 이름 해석/외부 연결 경로 문제가 실제 관측된 차단이다. DNS 실패의 하위 원인과 이를 복구한 후 내비 응답 여부는 미확인이다. SDK 결함이나 서버 폐쇄로 단정하지 않는다.

제품·시험 코드/권한/경로/네트워크 설정은 변경하지 않았다. 네트워크 복구 후 정상 내비 화면과 destination/recent callback 한 번 확인이 다음 작업이며 전체 suite 반복은 필요 없다. 원시 근거: root-navigation-official-sdk-retry-02.log, root-navigation-dns-02.log(local-only).

공식 출처: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro 및 initialization-and-release, declaring-permissions.

## 사용자 네트워크 연결 후 재확인 — 2026-11-15

사용자가 Wi-Fi 연결 상태 변경 가능성을 알린 뒤 직접 확인했다. 에뮬레이터에서 document.pleos.ai와 내비 gateway 모두 DNS 해석이 회복됐고 문서 서버 ping 응답을 받았다. gateway ICMP 무응답 자체는 서비스 장애로 해석하지 않았다. Mac의 기본 경로는 en0이며 networksetup SSID 조회는 비연결로 반환해 정확한 SSID는 확정하지 않았다. root가 공유기 자격증명 입력이나 네트워크 설정 변경을 하지는 않았다.

복구된 조건에서 동일 APK의 SDK 읽기 진단을 한 번 실행했다: 0.185초, CONNECTED → DESTINATION_REQUESTED → RECENT_REQUESTED → RECENT_RESPONSE_COUNT:0 → DESTINATION_RESPONSE, OK1. 이전 미응답과 달리 두 실제 SDK callback 수신을 확인했다. 최근 목적지 목록은 0개이며, 목적지 응답 필드의 유효성이나 실제 목적지 설정/도착 이벤트/앱 기억 연결 완료까지 검증한 것은 아니다. 내비 화면은 서비스 연결 중에서 검색·지도 UI로 진입했으나 캡처 시 지도 타일은 비어 있었다.

근거: .local/verification/stage7/root-navigation-network-restored-03.log. 제품/시험 변경 및 유료 API 호출 없음. 기존 DNS 실패는 보존하며 현재 SDK 응답 차단은 해소된 것으로 갱신한다.

## 실제 내비 목적지·최근 기록·저장 수락 — 2026-11-15

사용자는 UI/AI는 확인한 것으로 두고 내비만 재검증하도록 지시했다. 다른 작업자 및 UI/AI 회귀는 실행하지 않았다. 정상 내비 UI에서 공개 장소 더현대서울을 검색하고 목적지로 선택한 뒤 안내를 시작했다. 27km 경로 안내 화면의 목적지명이 시험값과 일치함을 확인했다.

기존 Application provider 관측은 AVAILABLE/UNDATED_RECENT1을 확인했지만 첫 응답에서 끝나 현재 목적지를 충분히 기다리지 않는 한계가 있었다. 제품 변경 없이 수동 전용 Stage7NavigationRealRouteTest 1개를 추가했다. 실제 제품 provider의 현재 목적지와 최근 목적지 두 종류 및 AtomicFile에 저장된 동일 시험 장소/유효 좌표/observedAt 양수/eventAt null을 최대20초 범위에서 확인했다. 단1회 0.087초 OK1, provider AVAILABLE, 메모리와 저장 파일 모두 UNDATED_RECENT 및 DESTINATION_OBSERVED를 확인했다. 시험 APK 빌드45초 exit0이며 제품 APK는 이전과 동일하다. 셸 run-as 조회는 non-debuggable 정책으로 거부되어 우회하지 않았고 정상 instrumentation 앱 context로 검증했다.

시험 경로는 정상 안내 종료 버튼으로 종료했고 검색 대기 화면 복귀를 확인했다. 시험 목적지 최근 기록과 앱 관측 기록은 보존했다. 실제 도착 이벤트는 재현하지 않았으므로 ARRIVAL_OBSERVED 실기기 수락이나 도착 시각/방문 확정은 주장하지 않는다. 이번 범위는 읽기 목적지·최근 기록·좌표·로컬 보존까지 통과다.

Stage7NavigationRealRouteTest는 더현대서울 경로를 먼저 설정해야 하는 수동 시험으로 무조건 전체 suite에 합산하면 안 된다. 원래 146개 결과와 기존 NoRoot는 보존한다.

근거: .local/verification/stage7/root-navigation-route-test-build-04.log, root-navigation-real-route-provider-04.log, root-navigation-real-route-durable-05.log.

제품 SHA256: 0c068687bf5651c30d70c5f5385a36375f18337712d698544827490ac3efcb8b
시험 SHA256: b650e6573ebc76ce9dd16357d1af559b91d74337651951055c09e46f668d428e

## 도착 이벤트 실제 수신·저장 재현 시도 — 2026-11-15

사용자의 도착 이벤트 확인 요청에 따라 root만 수행했다. 정상 내비에서 기존 시험 목적지 더현대서울 경로를 시작하고, 새 수동 검사 Stage7NavigationArrivalTest에서 실제 Application provider의 신규 ARRIVAL_OBSERVED와 파일 저장을180초 동안 기다렸다. 콜백/record/저장 파일 직접 주입은 하지 않았다. 제품 코드는 변경하지 않았다.

공식 emulator console geo fix로 목적지 접근3점을 입력했다. Android GPS 및 Pleos FusedLocation service callback에는 목적지 좌표 반영이 확인됐지만, 내비 화면은 수정구 시흥동/남은27km를 유지했다. 실제 검사180.091초, 1개 FAIL, fresh arrival persisted=false이며 provider AVAILABLE이다. 따라서 이번에 확인한 것은 내비의 실제 도착 재현·저장 수락 실패이고, 내비 내부 위치 수용/지도 매칭/도착 판단 중 어느 부분인지 원인은 미확정이다. 앱 저장 함수의 결함이나 SDK 도착 이벤트 미지원으로 단정하지 않는다.

별도 기존 JVM 단일 actualCallbackClockAndKindsDoNotInventArrivalTimeOrCoordinates는1/1 PASS(0.057초), Gradle16초 exit0였다. 이는 fake SDK callback→제품 provider→메모리 store 호출·관측시각·좌표 미조작·중복방지 계약 확인이며 실제 SDK 이벤트 전달 또는 실제 디스크 저장 성공을 대신하지 않는다. 앞선 실제 목적지/최근 기록 디스크 저장 통과는 유효하게 유지한다.

정리: 시험 안내 정상 종료/검색 화면 복귀, GPS는 시작 전 좌표·고도·속도로 복원했다(에뮬레이터 위성 수는 명령 허용범위8 사용; 원 dumpsys의 satellites0은 복구 완료로 주장하지 않음). 사용자 데이터 삭제/권한·제품 수정/유료 호출 없음. 실제 도착 수락은 여전히 미완료이며, 후속은 내비가 수용하는 위치/주행 시뮬레이션 경로 확인이다. 원 실패를 지우거나 반복 통과로 바꾸지 않는다.

새 Stage7NavigationArrivalTest는 수동 경로/GPS 조율이 필요한 검사로 일반 suite 무조건 실행 대상이 아니다. 원시 근거(local-only): root-navigation-arrival-build-06.log, root-navigation-arrival-real-06.log, root-navigation-arrival-contract-07.log.

제품SHA 0c068687bf5651c30d70c5f5385a36375f18337712d698544827490ac3efcb8b
시험SHA c7bc74f754bbf0a05576543f5ce1c27cbfa33ff605a73d4246a0cb7fd601c5ad

## Virtual Console 주행 대조와 공식 SDK 조사 — 2026-11-15

사용자 제안으로 Virtual Console 차량 신호를 추가한 별도 조건을 검사했다. 내장 브라우저 도구는 host unavailable이어서 페이지가 제공하는 동일 HTTP API를 소스에서 확인해 사용했다. 원 상태 P/0을 확보하고 Drive, 초기10km/h, accelerator15%를36초 적용했다. 실제 state 관측은0초10/12초26/24초55/35초77km/h였고 계기판 D/37km/h도 캡처했다. 동시에 공식 emulator geo fix로 시험 목적지 접근3점을 입력했다. 초기10km/h 고정 시험이 아니라 가속되는 주행 조건이었다. finally에서 speed/accelerator/brake0, Park, 원 GPS좌표로 복원했다.

내비는 시흥동/첫회전80m/남은27km를 유지했고 신규 도착 저장 관측은180.048초1FAIL이었다. 제품/시험 소스는 이전 도착 시도와 동일이다. 결과는 root-navigation-arrival-console-08.log, root-console-motion-08.json에 보존했다. 시험 안내도 정상 종료했다. 따라서 차량 속도와 GPS를 함께 바꾸는 것만으로 실제 내비 도착을 재현하지 못했으며, 저장 결함/SDK미지원으로 단정하지 않는다.

공식자료 조사:
- Vehicle2.3.2/Service2.3.21 소개와 DrivingMode/Odometer 목록: 차량 상태·제어 영역이며 공개 목록에서 위경도 설정/경로 재생/도착 이벤트 발생 API는 확인하지 못했다.
- Virtual Console의 공식 Motion 카드는 Gear/Brake/Odometer/Steering 등이다. 현재 제공 HTML에서도 latitude/longitude/gps/route 키는 없고 차량 controls/state API는 있다. 이것만으로 모든 내부 구현의 부재를 입증하지는 않는다.
- FusedLocation1.1.3은 위치 수신·proximity ENTER/EXIT를 제공한다. 공식 샘플은 geo fix와 별도 mock_location 설정 뒤 Inside/Outside를 안내한다. 실제 FusedLocationClient.setMockLocation은 Android GPS_PROVIDER에 setTestProviderLocation을 넣는 방식이므로 내비 전용 우회 주입이 아니다. 이 대안이 내비를 움직인다고 보장할 근거는 없다. 권한/샘플 설치는 수행하지 않았다.
- NaviHelper 공식 샘플 README는 응답/이벤트가 기본 내비 앱 상태와 지원에 의존하며 샘플 자체가 응답을 만들지 않는다고 명시한다. 현재 SDK 공개 함수에 getCurrentLocationInfo/getRouteStateInfo는 있으나 주행 시뮬레이션 시작 함수는 확인되지 않았다.

다음 권고: 가상 좌표 한 번 변경 전후에 FusedLocation 위치와 NaviHelper.getCurrentLocationInfo를 같은 시점에 비교하여 위치 전달 경계를 좁힌다. 내비 현재위치가 고정이면 공식 NaviHelper 샘플과 동일 get을 대조하거나 Pleos에 내비 앱 위치 공급자/지원되는 도착 재현 절차를 문의하는 것이 근거 있는 후속이다. 샘플 설치/권한 변경/문의 발송은 이번 읽기 조사에 포함하지 않는다. proximity ENTER는 목적지 주변 진입이지 내비 ARRIVAL이므로 현재 방문 확정 계약의 대체 수락으로 쓰지 않는다.

출처:
- https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/intro
- https://document.pleos.ai/docs/pleos-only/app-test-guide/virtual-console-guide
- https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/intro
- https://github.com/Pleos-Playground/pleos-sample-fusedlocation-app
- https://github.com/Pleos-Playground/pleos-sample-fusedlocation-app/blob/main/app/src/main/java/ai/pleos/playground/location/fused/sample/location/FusedLocationClient.kt
- https://github.com/Pleos-Playground/pleos-sample-navi-app


## FusedLocation과 NaviHelper 현재 위치 비교 — 2026-11-15

사용자 승인에 따라 root가 단 한 번의 두 지점 비교를 수행했다. 제품 변경이나 새 위치 권한 없이 기존 Pleos FusedLocation 서비스(user10, PID4424)의 실제 콜백 로그와 실제 NaviHelper.getCurrentLocationInfo 응답을 비교했다. 시험 전 Application navigation provider를 해당 instrumentation 프로세스에서 닫고 별도 SDK listener로 조회했으며 종료 시 listener/release를 정리했다.

| 관측 경로 | 시험 지점 A | 시험 지점 B | 결과 |
| --- | --- | --- | --- |
| Pleos FusedLocation 콜백(위도, 경도) | 37.4029983333, 127.1029983333 | 37.5260266667, 126.92731 | 입력 좌표로 변경 |
| NaviHelper 현재 위치(위도, 경도) | 37.4127212, 127.094681 | 37.4127212, 127.094681 | 동일 좌표 유지 |

두 NaviHelper 요청은 각각33ms/12ms에 실제 응답했다. 두 번째 Fused 콜백 뒤 약20초 관측창을 두었으며 새 instrumentation 1개는20.092초 PASS이다. 이 PASS는 두 SDK 응답 수신 조건 통과이며 위치 변경/도착 성공이라는 뜻이 아니다. 현재 증거는 emulator GPS→Pleos 위치 서비스까지 전달되지만 NaviHelper가 보고하는 내비 위치는 반영하지 않는다는 경계를 보여 준다. 내비의 위치 공급자/캐시/지도 매칭 중 어느 내부 원인인지는 확정하지 않는다. 실제 ARRIVAL_OBSERVED 및 그 디스크 저장 수락은 계속 미완료이며 기존 실제 목적지/최근 목적지 저장 성공은 유지한다.

시험 APK 빌드47초 exit0. 제품 SHA는 0c068687bf5651c30d70c5f5385a36375f18337712d698544827490ac3efcb8b 그대로, 시험 SHA는03199081fc405e037ca9af81ff3275dc494b61296f73b5a0de1d4975283b3259이다. 추가한 Stage7NavigationLocationComparisonTest는 두 지점 GPS 조율이 필요한 수동 진단이며 일반 무인 회귀 조건이 아니다. 제품/API/권한/실제 음성/데이터 삭제/Git 작업은 수행하지 않았다. 종료 후 원 GPS 위도37.421998/경도-122.084000/고도5/속도0을 복원하고 dumpsys의37.421997 반올림값을 확인했으며 정상 MainActivity를 열었다.

로컬 근거: root-location-comparison-build-09.log, root-location-comparison-09.log, root-location-comparison-09.json. 추가 전체 검사나 도착 재시도는 하지 않았다. 다음 필요한 정보는 내비 앱이 사용하는 위치 공급자 또는 해당 이미지에서 지원하는 내비 주행/도착 시뮬레이션 절차다.


## 7단계 사용자 수락 및 후속 이월 — 2026-11-15

사용자가 내비 위치 문제를 이슈로 남기고 7단계 완료 여부를 확인하도록 요청했다. UI/AI의 앞선 수락과 이번 내비 후속 이월을 합쳐 7단계 기능 개발 완료로 정리한다. 실제 도착 수신/저장 검증 공백은 GitHub #4(https://github.com/chowonbhin/Pleos/issues/4)에 재현·관측·코드 위치·종료 기준과 함께 등록했다. GitHub connector의 작성 권한403 뒤 기존 계정 CLI 인증으로 생성했고 이슈 URL을 확인했다. 제품 변경·추가 시험·코드 push는 하지 않았다.

Ruling: 내비 위치 미갱신과 실제 도착 검증 공백은 7단계 완료 차단에서 후속 #4로 이월 — 사용자 최신 수락에 따름 — 실제 도착을 이용한 시연은 지원 위치 입력 경로 확보 전까지 보장할 수 없음. 기존 실패 증거를 유지하며 모든 검증 통과 또는 내비 원인 해결을 주장하지 않는다. main 통합/게시와 8단계 착수는 별도 작업이다.


## 2026-11-15 게시용 최종 확인

- `feature/07-memory-links` 최종 소스에서 JVM 323개 통과(실패·오류·skip 0), backend fake 190개 통과.
- `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` exit0, 1분6초. Lint 오류0, 경고36. 캐시 가능한 작업은 Gradle이 재사용했다.
- V 동결233경로는 모두 동일하며 root 후속 진단과 문서만 추가됐다. 제품 APK는 `0c068687bf5651c30d70c5f5385a36375f18337712d698544827490ac3efcb8b`, 최신 시험 APK는 `03199081fc405e037ca9af81ff3275dc494b61296f73b5a0de1d4975283b3259`. 기기 UI/실API/내비 검사는 반복하지 않았으며 기존 수락·실패 기록을 유지한다.
- 전송 소스·staged diff·도달 가능한 Git blob 이력과 APK 내부를 검사하고, 실제 로컬 비밀값을 값 출력 없이 대조했다. 새 전송 파일은 모두 텍스트이며 기존 PDF는 이전 검사본과 바이트 일치한다. 키·세션·예산·원시 로그·APK·서명키는 전송하지 않는다.
- 게시 순서: 원격 `feature/07-memory-links` 먼저 push → 최신 로컬 `main`에 병합 → 병합 트리와 검증 feature 일치 확인 → 원격 `main` push. 게시 성공 여부와 최종 커밋은 최종 응답 및 원격 ref로 확인한다.
