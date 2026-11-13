# 개발 상태와 재개 안내

기준: 2026-11-14. 사용자는 원래 설계를 유지하고, 가능한 저장 코어 작업을 마친 뒤 **3단계 미완료 상태로 중단**하도록 결정했다. 주차 화면에 음성 입력을 추가하거나 OS 주행 제한을 해제하는 대안은 채택하지 않았다.

## 완료 범위와 남은 범위

| 범위 | 상태 |
| --- | --- |
| 1단계 초기 환경·빈 앱 | 완료. 독립 clone 빌드·실행 확인 |
| 2단계 플랫폼 실험 | 완료. 실제 오프라인 STT 결과를 사용자가 확인했고 차량 상태·OS 제한을 기록 |
| 3A 저장 코어 | Room 스키마·시드·직접 메모·음성 원문 원자 저장·AI 대기 데이터·조회 및 테스트 구현 |
| 3단계 전체 | **미완료**. 실제 화면·STT 저장 경로·앱 프로세스 재시작·차량 안전 통합은 아직 없음 |
| 4단계 이후 | 미착수 |

현재 `MainActivity`는 초기 화면이다. 저장 코어를 구현했다는 사실이 사용 가능한 메모 화면이나 전체 데모 완성을 뜻하지 않는다. 이 단계에는 네트워크 API·Worker·실제 AI 호출도 없다. 따라서 3단계 완료 릴리즈를 생성하지 않는다.

3A의 Room 저장은 Note·정책·AI job·후보 snapshot을 같은 트랜잭션으로 처리한다. 같은 음성 세션은 하나의 메모만 만들고, 원문은 수정하지 않는다. 방문이 없어도 저장하며 후보는 원래 날짜와 녹음 시각을 기준으로 고정한다. AI 제안과 사용자 확정은 조회 결과에서도 구분한다. 사진은 후속 데이터 계약만 있으며 스캔·사진 연결 기능은 아직 없다.

메인이 실행한 현재 코어 검증은 JVM **8/8**, 실제 Room **27/27**, Lint **오류 0·경고 11**이다. 앱과 테스트 APK 빌드도 성공했다. Room 복원 테스트는 별도 UUID 이름의 파일 DB를 닫았다 다시 여는 검사다. **앱을 종료·재실행하는 전체 제품 시연을 대신하지 않는다.** 남은 경고는 기존 버전 관련 경고이며, SDK XML 도구 버전 경고도 별도 환경 점검 대상이다.

`allowBackup=false`와 두 형식의 백업 제외 규칙을 적용했고 APK에 포함된 규칙을 확인했다. 로컬 테스트 DB, 키·로컬 설정, APK·로그·화면 자료는 Git에 포함하지 않는다. 저장소의 Room schema JSON은 실제 사용자 DB가 아닌 구조 정의이며, 시드 방문과 테스트 문장은 합성 시연 데이터다.

## 안전 연동의 미해결 사항

상세 관측과 재현 절차는 [플랫폼 실측 기록](environment/mv-02-platform-spikes.md)에 있다.

- 현재 Pleos 이미지에서 주행용 Activity도 OS 차단 화면에 가려진다. 설치 출처 거부 기록이 있지만, 지원되는 개발 설치 절차는 추가 확인이 필요하다.
- 콘솔 원천 연결이 끊겨도 표준 차량 API가 정상값을 계속 반환했다. 원천 단절을 신뢰성 있게 UNKNOWN으로 바꾸는 계약은 해결되지 않았다.
- 오프라인 STT 자체의 성공을 실제 주행 중 음성 저장 기능 완료로 확대하지 않는다.
- 가상 기기의 네트워크 설정 복원과 실제 인터넷 재접속 여부는 구분해 확인해야 한다.

원안의 UNKNOWN 잠금·주행 중 편집/공유 제한·필수 음성 기능을 삭제하거나 완화하지 않는다. 다음 UI·플랫폼 통합 전에 위 조건을 해결하거나 사용자에게 구체적인 범위 변경 승인을 받아야 한다.

## 코어 검증 재현

[README](../README.md)의 JDK 17·SDK 및 로컬 디렉터리 준비 후 실행한다. 프로젝트에서 제외된 파일을 복사할 필요는 없다.

```sh
./gradlew --no-daemon --console=plain testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

Room 검증에는 전용 새 AVD와 전용 ADB 서버를 사용한다. `MV_SERIAL`과 `MV_USER_ID`를 그 기기의 실제 값으로 설정하고, 앱과 테스트 APK를 **기존 앱이 없는 새 기기**에 설치한 뒤 아래 명령을 실행한다. 다른 프로젝트 기기에 덮어쓰거나 서명 충돌을 해결하려 기존 데이터를 삭제하지 않는다.

```sh
: "${MV_SERIAL:?전용 새 AVD serial을 설정하세요}"
: "${MV_USER_ID:?그 AVD의 실제 사용자 번호를 설정하세요}"
: "${ADB_SERVER_SOCKET:?전용 ADB 서버를 설정하세요}"
"$ANDROID_HOME/platform-tools/adb" -s "$MV_SERIAL" shell am instrument \
  --user "$MV_USER_ID" -w -r \
  -e class app.memoryvault.data.VisitDaoTest,app.memoryvault.data.MemoryRepositoryTest,app.memoryvault.data.AiLinkQueueTest,app.memoryvault.data.MemoryPersistenceTest,app.memoryvault.data.NotePresentationTest \
  app.memoryvault.test/androidx.test.runner.AndroidJUnitRunner
```

`am` 명령의 exit code만 확인하지 않고 `OK (27 tests)`와 개별 실패 유무를 확인한다. 테스트는 in-memory DB 또는 자신이 만든 `mv03a-test-<UUID>.db`만 사용·정리한다. 마이크·차량 정책·네트워크 설정을 바꾸는 테스트가 아니다.

## Git 운영 기준

2026-11-14 사용자 정정에 따라 이전 계획의 develop 중심 운영 대신 아래 흐름을 따른다.

1. 검증된 최신 `main`에서 작업 브랜치를 만든다.
2. 해당 기능의 구현·검증·독립 검토가 끝나면 `main`에 병합한다.
3. 릴리즈 완료 기준을 충족한 경우에만 그 커밋에서 릴리즈 브랜치와 버전 태그를 만든다.
4. 원격 push 전에는 feature/main/릴리즈의 정확한 파일·이력·커밋 범위를 보여 주고 사용자 확인을 받는다.

현재 완료 기준선은 2단계 `0c5383d`다. 미완료 3단계의 진행분은 `feature/03-offline-memory`에 보존하고, `main`에 완료된 기능처럼 병합하거나 릴리즈로 표시하지 않는다. 기존 develop과 작업 브랜치는 임의로 삭제하지 않는다. 다음 작업은 사용자의 재개 요청 이후에 진행한다.
