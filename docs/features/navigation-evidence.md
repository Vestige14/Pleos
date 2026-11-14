# 7단계 내비 근거 수집 — 구현 인계

이 모듈은 기본 내비 앱이 제공하는 목적지 정보를 읽는다. **현재 구현은 NOT_RUN_BY_DESIGN**이며 빌드, 독립 코드 검토, 실제 Pleos 제공자의 지원 확인은 별도 검증자 V가 담당한다. 시드 방문이나 캡처 위치를 생성하는 기능은 아니다.

## 조립

root가 `ai.pleos.playground:NaviHelper:3.0.5` 의존성과 일반 권한 `pleos.car.permission.NAVI_ROUTE`를 추가한다. 현재 Maven 저장소 설정은 재사용한다. root 소유 AppContainer에서 다음 객체를 Application 수명에 맞춰 한 번 생성·시작하고, 동일 객체를 C의 `NavigationEvidenceProvider` 입력으로 전달한다.

```kotlin
val navigationEvidence = PleosNavigationEvidenceSource(applicationContext)
navigationEvidence.start()
// C에는 NavigationEvidenceProvider로 전달한다.
// 소유 수명이 끝나면 navigationEvidence.close()를 호출한다.
```

생성자와 `snapshot()`/`status()`는 SDK·디스크를 조회하지 않는다. `start()` 이후 전용 단일 실행기에서 파일을 읽고 SDK에 연결한다. 실제 연결 callback 뒤 listener를 등록하고 현재/최근 목적지 getter를 각각 한 번 호출한다. 목적지 변경 callback이 있으면 현재 목적지만 다시 조회한다. 이미 조회 중이면 추가 요청을 합치고 이전 세대 응답을 버린 후 한 번 조회한다. 최근 목록을 주기적으로 조회하지 않는다.

최초 연결은20초, getter 응답은5초 상한이다. SDK 자체 초기 연결 재시도와 구분한다. 한 getter가 응답하지 않으면 그 경로의 자동 조회를 중단한다. 일부 getter 또는 실제 도착 callback만 지원해도 받은 근거는 사용한다. 오류·INACTIVE·응답 전부 부재는 UNAVAILABLE이며 재시도 루프를 만들지 않는다. `start()`는 중복 호출을 무시하고 `close()`는 종료 상태다. 다시 연결하려면 소유자가 새 객체를 명시적으로 만든다.

`AVAILABLE`은 실제 정보/도착 callback을 받았다는 뜻이다. 연결 성공만으로 AVAILABLE을 만들지 않는다. 정상 빈 목록/목적지 없음 응답도 AVAILABLE일 수 있다. SDK가 최초 연결 이후 binder 단절을 공개 callback으로 항상 전달하는지는 확인되지 않았으므로 지속적인 연결 건전성 보장은 아니다. `snapshot()`에는 UNAVAILABLE/종료 중에도 유효 기간 내 이전 관측이 남을 수 있다. 현재 활성 경로 목록으로 해석하지 않는다.

## 출처·시각·위치

| kind | 의미 | observedAtMillis | eventAtMillis | 좌표 |
| --- | --- | --- | --- | --- |
| DESTINATION_OBSERVED | 조회/응답 당시 활성 목적지 관측 | callback 수신의 실제 로컬 wall clock | null | SDK의 latitude/longitude가 둘 다 유효할 때만 |
| UNDATED_RECENT | SDK가 반환한 날짜 없는 최근 목적지 목록 | 목록 callback 수신 시각 | null | 해당 최근 항목의 유효 좌표만 |
| ARRIVAL_OBSERVED | 실제 도착 callback 수신 | 도착 callback 수신 시각 | null | null |

공식 navi-data3.0.4의 DestinationArrivedInfo에는 name/address/duration/distance만 있고 시각·좌표가 없다. 이동 시간을 빼서 도착 시각을 계산하거나 이전 목적지의 좌표를 도착 좌표로 복사하지 않는다. 위 관측 시각은 실제 설정/방문/역사적 도착 시각이 아니다. C는 공유 계약 v1.1에 따라 관측일과 경험일을 구분하고, 최근 목록은 evidenceDate=null/occurredAt=null로 유지한다. 같은 장소의 날짜 없는 항목과 실제 도착 관측을 병합해 날짜를 붙이지 않는다.

이름이 없거나200codepoints를 넘으면 항목을 만들지 않는다. 빈/과도한 주소는 null, 누락·비유한·범위 밖 좌표는 쌍으로 null이다. 유효한0좌표는 누락으로 취급하지 않는다. 현재 목적지의 같은 payload 반복은 변경/취소/도착 전까지 중복 보관하지 않는다. 같은 도착 payload가30초 안에 중복 도착하면 한 번만 보관한다. 이 중복 억제는 실제 방문 횟수 계산 기능이 아니다.

## 로컬 보관과 종료

관측은 앱의 `noBackupFilesDir/navigation-evidence/observations-v1.json` 한 파일에만 원자 저장한다. 메인 Room DB·원본 음성·사진·공유 선택은 변경하지 않는다. 최대256행, 그중 날짜 없는 최근 항목 최대20행, 최대1MiB이고30일 유효 기간을 적용한다. 조회 결과는 최신 관측부터 반환하며 만료/현재 시계보다 미래인 관측은 노출하지 않는다. 디스크 정리는 시작/변경 저장 시 수행한다. 프로세스가 꺼진 동안 정확한 만료 시각에 디스크 삭제를 실행하는 서비스는 없다.

최근 목록의 빈 응답은 이전 UNDATED_RECENT만 교체하며 이전 목적지/도착 관측을 지우지 않는다. 경로 취소 callback도 이전 근거를 보존한다. 파일 손상·알 수 없는 버전·읽기 실패는 기존 파일을 덮어쓰지 않고 UNAVAILABLE로 남긴다. 저장 실패 시 수신한 메모리 관측은 남지만 재시작 보존은 보장하지 않는다. close는 이후 callback을 차단하고 등록 listener 제거와 SDK release를 전용 실행기에 요청한다. 이미 처리 중인 저장은 마칠 수 있다.

이 모듈에는 경로 설정·취소·재탐색·지도 화면 열기·현재 기기 위치 getter·마이크·외부 API·키/세션 입력·payload 로그가 없다. 사용자 경로를 변경하거나 시스템 권한을 자동 부여하지 않는다.

## V에게 남긴 필수 사례

모든 사례는 **NOT_RUN_BY_DESIGN**이다. 검증 코드도 구현자가 작성하지 않았다.

1. fake SDK/시계/저장소/실행기로 start 전 empty+NOT_READY, 두 번 start 시 연결1회, 연결 callback 전 getter0회, callback 뒤 각 getter1회를 확인한다. 연결 반환만으로 AVAILABLE이 되지 않아야 한다.
2. 비동기 현재 목적지의 이름·주소·실제 좌표/관측 시각 및 eventAt=null을 확인한다. 빈 이름·위도NaN·경도범위초과·한쪽 좌표누락·유효(0,0)를 각각 확인한다.
3. 최근 목록은 UNDATED_RECENT/eventAt=null이며 과거 도착으로 승격되지 않는다. 빈 목록은 최근 항목만 제거한다. 현재 목적지와 같은 장소라도 kind/ID를 합치지 않는다.
4. 목적지 변경 중 in-flight 응답은 버리고 getter1회를 추가한다. 연속 변경을 합치며 실패/timeout 이후 변경 이벤트로 unsupported getter를 반복하지 않는다.
5. 실제 arrival callback의 name/address와 callback 관측 시각만 보관하고 이전 목적지 좌표·duration으로 위치/이벤트 시각을 만들지 않는다. 중복 도착과30초 이후의 새 관측을 구분한다.
6. routeCancelled는 SDK의 cancelRoute를 호출하지 않고 이전 근거를 유지한다. 취소 이전 getter의 늦은 응답을 현재 새 관측으로 만들지 않는다. 후속 목적지 변경 뒤에는 새 조회가 가능하다.
7. 연결 실패/20초 timeout, getter 둘 다5초 timeout, 일부 getter만 성공, 빈 성공, getter 예외, INACTIVE/error를 구분한다. 실패가 원문/사진/공유를 막거나 자동 무한 재연결을 만들지 않아야 한다.
8. close 전/초기화 중/조회 중/저장 중/반복 close와 늦은 connected·정보·도착 callback을 확인한다. listener 제거와 release, 종료 후 추가 getter/행 생성0, status=UNAVAILABLE을 확인한다.
9. 전용 파일에서 재생성·원자 쓰기 실패/복구·손상UTF-8/JSON/버전/초과 파일을 확인한다. 손상 파일을 덮어쓰지 않고 기존 메인 DB/원음/다른 파일을 보존해야 한다.
10. 256행/최근20행/30일/미래값/대형 문자열/1MiB 상한을 확인하고 snapshot 호출이 SDK나 파일을 읽거나 값을 변경하지 않는지 확인한다.
11. 통합본에서 C가 DESTINATION_OBSERVED의 관측일을 방문/설정일로 표시하지 않고, 날짜 없는 최근 항목을 특정 날짜 후보로 전송하지 않는지 확인한다. 신규 주행 UI/자동 외부 열기는 없어야 한다.
12. 전용 Pleos AVD에서 V가 실제 getter 한 쌍을 한 번만 수행하고 실제 지원/정상 빈 결과/응답 부재를 기록한다. 기존 사용자 목적지 설정·취소·경로 변경이나 반복 unsupported polling을 하지 않는다. 실제 도착 callback은 기존 경로를 건드리지 않고 관측 가능한 경우에만 확인하며, 불가하면 fake 근거와 실제 미검증을 분리한다.

## 공식 근거

- [의존성3.0.5](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/importing-sdk), [권한별 API](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/declaring-permissions)
- [연결 callback/해제](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/initialization-and-release), [비동기 목적지 조회](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/destinations), [변경·도착 listener](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/event-listeners)
- [공식 샘플](https://github.com/Pleos-Playground/pleos-sample-navi-app)
- [공식 Helper 소스](https://nexus-playground.pleos.ai/repository/maven-releases/ai/pleos/playground/NaviHelper/3.0.5/NaviHelper-3.0.5-sources.jar), [공식 POM](https://nexus-playground.pleos.ai/repository/maven-releases/ai/pleos/playground/NaviHelper/3.0.5/NaviHelper-3.0.5.pom), [공식 데이터 DTO 소스](https://nexus-playground.pleos.ai/repository/maven-releases/ai/pleos/playground/navi-data/3.0.4/navi-data-3.0.4-sources.jar)

공식 AAR의 manifest는 minSdk23과 라이브러리 자체의 service query/메타데이터를 선언한다. 앱의 기존 minSdk29를 올리는 변경은 요청하지 않는다. 이것은 배포 메타데이터를 읽은 결과이며 통합 빌드·기기 호환 검증을 대체하지 않는다.
