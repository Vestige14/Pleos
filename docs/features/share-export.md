# 공유 선택 · 미리보기 · PNG 저장

5단계 공유 기능 안내다. 전체 UI 다듬기는 후속 P2/3D 기억 맵과 함께 진행하며 여기서는 기존 MaterialTheme와 필수 조작만 추가한다. 실제 기기 수락 결과와 미검증 범위는 아래 검증 절을 구분해 읽는다.

## 실행하기

[SETUP](../SETUP.md)에서 JDK17/SDK35·같은 서명·전용 Pleos 기기를 확인한다. 제품 APK는 `app/build/outputs/apk/debug/app-debug.apk`, 시험 APK는 `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`다. 기존 설치의 데이터를 지우거나 다른 기기의 설정을 바꾸지 않는다. 공유 시험에는 마이크나 AI 연결이 필요하지 않다.

1. 공식 콘솔 P·0과 앱의 실제 주차 상태를 확인하고 **기억 저장소**의 하루 초안을 연다. 사진 권한·스캔·후보 확정/해제는 [사진 안내](photo-linking.md)를 따른다.
2. **이날 공유하기**를 누른다. 처음에는 해당 날짜의 모든 방문과 접근 가능한 후보/확정 사진만 선택한다. 사진의 후보 상태를 자동 확정하지 않는다. 새로 스캔한 사진도 저장된 선택에 자동으로 추가하지 않는다.
3. 카페의 **방문 공유에 포함**을 끄고, 해변의 p3을 사진 순서와 실제 썸네일로 확인해 해제한다. p6을 하루 초안에서 연결 해제했다면 공유 후보에도 나타나지 않아야 한다. **공유용 설명**에 설명을 직접 쓴다. 개인/음성 메모는 자동으로 복사되지 않는다.
4. **미리보기**를 누른다. 날짜·순서가 다시 매겨진 식당/해변·고른 사진·trim된 설명만 보여야 한다. 포함 방문이 0개이거나 선택 저장이 실패하면 미리볼 수 없다. 저장 실패는 **다시 저장**으로 재시도한다. 선택으로 돌아가면 제외와 설명이 유지된다.
5. **이미지 저장 · 공유**를 누른다. 완전한 PNG를 `Pictures/기억저장소/`에 새 이름으로 저장한 뒤 Android 공유 시트를 연다. 공유 시트가 닫히거나 공유 결과를 확인하지 못해도 파일은 남고, 다음 주차 하루 초안에서 파일명과 사진 앱/파일 앱 폴더 안내를 볼 수 있다. 수신 앱 부재나 공유 완료를 단정하지 않는다. 사진 원본을 수정하지 않는다.
6. 공유 시트를 떠난 뒤 주차 하루 초안의 **최근 저장한 이미지**에서 파일명과 `Pictures/기억저장소` 안내를 확인한다. 앱 재실행 뒤에도 마지막으로 확인된 공개 이미지의 안내가 남아야 한다. 이 표시는 저장 사실이며 외부 공유 완료 표시가 아니다.
7. 생성한 PNG를 실제로 열어 폭1080, 카페·p3·p6·개인/음성 메모·좌표·주소 없음, 사진 방향·한글·emoji 줄바꿈·마지막 내용까지 확인한다. 사진 픽셀 안에 얼굴·영수증·글씨가 있으면 그대로 보인다. EXIF 제거는 사진 속 내용을 가리는 기능이 아니다.
8. 선택 저장 표시가 끝난 뒤 하루 초안으로 돌아갔다가 재진입하고, 총괄이 조정한 프로세스 종료/재실행 후에도 제외와 설명이 유지되는지 확인한다. 날짜별 선택은 별도 `share-selections.db`에 보존한다. 저장 도중 프로세스 자체가 강제 종료되면 아직 커밋하지 못한 입력까지 보장하지 않는다.

공유 선택/미리보기에서 출발하거나 UNKNOWN/TRANSITION이 되면 입력·미리보기를 숨기고 읽기/저장/chooser 작업을 취소한다. 주차 복귀 후에는 하루 초안에서 사용자가 공유를 다시 열어야 한다. ON_STOP에서도 네이티브 화면·키보드를 숨기고 작업을 무효화하며, 차량 상태/foreground 세대를 디스크에서 주차 상태로 복원하지 않는다. OS 공유 시트가 이미 열린 뒤 다른 앱의 동작을 통제한다고 주장하지 않는다.

## 데이터와 실패 처리

- `ShareInputMapper`는 날짜·방문 ID/이름/정렬 시각·유효 사진 URI/상태만 옮긴다. 미리보기와 renderer는 `ShareDraft`의 날짜·방문명/순서·사진·공유 설명만 소비한다. Note·좌표·주소·제외 방문은 공유본 필드에 없다.
- 선택 변경은 순서대로 저장한다. 이미 수락된 저장은 화면 종료와 경합해도 완료하며, 새 controller는 날짜별 저장 결과를 읽는다. 읽기 실패를 새 기본 선택으로 덮어쓰지 않는다.
- 기존 renderer의 폭1080·높이12000·메모리128MiB·PNG16MiB·텍스트64000 UTF-16 단위 상한을 유지한다. 초과는 명시 오류이며 조용한 잘림/부분 PNG 성공을 허용하지 않는다. 새 PNG로 인코딩하며 원본 EXIF/GPS를 복사하지 않는다.
- 내보내기 시작·각 사진 열기/읽기·파일 공개 직전·chooser 직전에 현재 PARKED/foreground/무효화 세대를 확인한다. 공개 및 chooser 전에는 고른 원본 URI도 다시 열어 현재 읽기 가능 여부를 확인한다.
- MediaStore는 `image/png`, `IS_PENDING=1`로 생성해 쓰기·닫기 완료 후 0으로 공개한다. 실패/취소 정리는 이번 insert가 만든 URI 하나와 아직 pending인 행에만 한정한다. 공개 뒤 chooser 오류나 늦은 취소 때문에 이미 공개된 파일을 삭제하지 않는다. provider가 publication을 커밋한 뒤 오류를 던지는 경우도 조건부 정리로 공개 파일을 보존한다.
- Android provider/native decode가 모든 순간 즉시 중단된다는 보장은 없다. CancellationSignal과 읽기 전후 guard, 후속 경계 확인으로 무효화 뒤 새 결과 반환·공개·자동 chooser를 막는다. provider 자체가 정리를 거절하는 경우에는 정상 저장으로 안내하지 않는다.

MediaStore pending·경로 계약은 [Android 공유 미디어 안내](https://developer.android.com/training/data-storage/shared/media), chooser의 ClipData/read grant는 [Intent 공식 계약](https://developer.android.com/reference/android/content/Intent)을 따른다.

## 저장 영수증 (fix1)

공개 성공 직후, chooser를 호출하기 전에 앱 내부에 최신 공개 이미지의 URI·실제 파일명·날짜만 동기 저장한다. 개인 메모·원본 사진/좌표/설명은 영수증에 넣지 않는다. 차량 상태나 Activity 화면을 저장 결과로 복원하지 않으며 기존 백업 제외 규칙을 따른다.

chooser 오류·취소·Activity 종료가 뒤따라도 확인된 공개 파일을 삭제하지 않고, 다음 주차 하루 초안에서 마지막 저장 사실과 폴더 힌트를 제공한다. 아직 공개하지 못한 실패는 새 영수증을 만들지 않는다. 영수증 보관 자체가 실패하면 공개 파일은 유지하고 그 실패를 구분하며, 영수증 저장을 앞질러 chooser를 실행하지 않는다. 안내는 같은 날짜·foreground·최신 주차 상태/generation에서만 보이며 새 외부 열기/공유 버튼은 추가하지 않는다.

## 검증과 현재 한계

- 기존 renderer/코어8파일은 prior-manifest와 byte-identical이다. 최초5단계의 RED/GREEN·수동 사진 선택/복원 및 1080PNG 수락 이력은 task 보고서와 인수인계를 따른다.
- fix1 JVM RED는24개중6실패였고, 영수증 순서/취소/출발/미공개 실패/저장 실패를 구현한 뒤 공유47개가 통과했다. 이후 최신 snapshot 읽기의 Compose Lint 경계를 분리하고 stale generation 회귀를 더했다.
- 현재 fix1 소스의 전체 JVM226개(공유48개) 실패·오류·skip0, Lint 오류0·경고30·hint2, 제품/시험 APK 빌드 성공을 확인했다. 추가 UseKtx warning은 동기 commit의 실패 Boolean을 확인하려 직접 API를 사용한 부분이며 suppression하지 않았다.
- 실제 SharedPreferences 재구성/3필드 제한·기존 주차 화면의 새 저장 알림·재진입 복원·비주차 숨김/외부 action 없음의 Android4개는 no-op 경계에서 모두 RED(1.967초)를 확인했다. 같은2class/4메서드가 실제 구현에서4/4 GREEN(2.520초)이었고 NoRoot/harness 오류는 없었다. 저장소 재구성·화면 재생성·이미 열린 주차 화면 갱신·비주차 숨김과 외부 action 없음의 범위다.
- 기존 자동 UI NoRoot([이슈 #2](https://github.com/chowonbhin/Pleos/issues/2))와 시스템 인쇄 수신 앱 NPE의 원인은 해결됐다고 주장하지 않는다. 공유 시트를 dispatch한 사실을 공유 완료로 보지 않는다. 과거 수동 UI/PNG 성공과 자동 검사 실패도 구분한다.
- 최종소스의 전체 공유29개·통합 앱 기기 검사, fix1의 실제 앱 주차 안내/force-stop 보존, 실제 D/UNKNOWN/OS권한철회 내보내기 조합·ContentResolver negative/cancellation은 총괄의 남은 수락 범위다. 기존 ON_STOP/주행 안전 닫기는 그대로 유지했다. 새 독립 scoped 재검토는 같은 reviewer가 맡는다.

실행/검증 소스·APK SHA·미검증은 `.superpowers/sdd/2026-11-14-share-ai-parallel/task-1-report.md` 및 `task-1-fix1-report.md`와 총괄 [인수인계](../HANDOFF.md)를 따른다. 로컬 task 기록은 영구 안내의 필수 의존성이 아니다. **최신 사용자 결정은 5·6단계 진행**이다. 6단계는 AI 통신·실제 API 수락 및 원본 음성/STT/선택적 캡처 위치 저장 기반을 마무리하고, 사진 내용 기반 기억 연결 확장은 7단계로 넘긴다. 위치를 얻지 못하면 위치 정보 없음으로 저장하며 음성/STT 저장을 막지 않는다. UI 다듬기는 P2이며 후속7/8 착수·merge/push/Release의 승인 경계는 유지한다.


## 총괄 후속 수락 — 2026-11-14

위 작업자 인계 당시의 미검증 목록에 대한 후속 결과다. 제품 fix1 이후 신규 실패 경계 시험만 추가했으며 제품 APK는 동일하다.

- 실제 일반 앱 진입에서 선택·미리보기·PNG 공개 및 다음 하루 초안의 파일명/폴더 안내를 확인했다. 프로세스 종료 후 재실행에서도 안내가 유지됐다. 실제 합성 PNG의 선택한 식당/해변 사진·설명, 제외 방문/사진, 이미지 크기와 메타데이터 청크를 확인했다. 실제 fixture에는 개인 메모가 비어 있어 비어 있지 않은 메모 제외 증거는 별도 코어 시험이다.
- 실제 콘솔 D에서 공유/사진/저장 안내가 숨겨지고 P 복귀 뒤 자동 재진입하지 않음을 확인했다. 정상 시스템 화면에서 사진 권한 거부→허용, 차량 속도 권한 거부에 따른 UNKNOWN→허용을 확인하고 원래 허용 상태로 복구했다. 선택/설명/저장 안내가 보존됐다. 사진 권한 거부 중 내보내기 완료나 provider decode 중 즉시취소를 이 검사로 주장하지 않는다.
- fix1 전체 기기 회차는94메서드 중93성공·1실패였다. 공유29개는 통과했고 기존 VoicePanelScreenTest 첫 표시 assertion의 NoRoot가1개 남았다. [기존 이슈 #2](https://github.com/chowonbhin/Pleos/issues/2)를 유지하며 이 회차를 전체 성공으로 표시하지 않는다.
- 이후 추가한 AndroidShareFailureBoundaryTest3개는 실제 사진 삭제 후 재접근 실패·읽을 수 있는 손상 이미지·쓰기 완료 후 공개 전 generation무효화를 검사했고 단1회3/3통과(0.783초)했다. 자기pending출력만 정리되고 다른 공개 이미지/기존 영수증이 보존됨을 확인했다. 새3개 통과를 기존 전체 회차와 합쳐 새 전체 suite 성공이라고 표시하지 않는다.
- 저장 안내 유실 수정 I1은 독립 scoped 검토 Approved이며, 추가 실패 시험1파일도 독립 검토에서 Critical/Important/Minor0으로 Approved다. 전체 feature 최종 검토·최종 통합 검증·게시 판단은 별도다.
- [공유 시트 종료 이슈 #3](https://github.com/chowonbhin/Pleos/issues/3): PNG 공개 이후 공유 시트가 곧 닫히는 현상이 남는다. 외부 수신 앱 전달 성공을 확인하지 못했으며, 저장 PNG와 지속 안내를 통해 저장 사실은 확인할 수 있다. 시스템 인쇄 앱 예외·제품 lifecycle·resolver의 원인 관계는 미확정이다.

blocked provider/native decode 임의 시점의 즉시취소, 저장 공간 고갈·모든 provider 오류, 모든 수신 앱 호환성까지 검증한 것은 아니다. 이러한 한계와 기존 NoRoot/공유 시트 이슈를 숨기거나 안전 gate·assertion을 완화하지 않는다.

### 전체 feature 검토 직전 최종 회차

추가 시험까지 포함한 현재 APK에서 전체 기기 검사를 필터 없이 단1회 실행했다. **97메서드 중96성공·1실패,44.893초,skip0**, 공유32개는 모두 통과했다. 실패는 기존 `MemoryVaultScreenTest.anotherTargetRequiresExplicitDiscard_andCancelPreservesTheSingleUnsentDraft:136`의 최초 메모 추가 scroll/click에서 NoRoot다. 이전94회차 VoicePanel 실패와 구분하며, 기기 전체GREEN으로 표시하지 않는다. 현재 양APK/코드43은 실행 전후 일치했다. 전체 JVM/Lint/양APK 명령도 exit0였고 JVM226/실패·오류·skip0, Lint오류0·경고30·hint2다. 전체 feature 독립 최종검토와 통합/게시 판단은 후속이다.
