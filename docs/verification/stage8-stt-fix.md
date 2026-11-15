# 8단계 STT 발화 누락 수정 및 릴리즈 준비

2026-11-15. 사용자가 “일단 릴리즈 준비하자. 누락 해결하고 재확인하고”라고 지시해 root가 직접 수정·검증했다. **제공된 녹음3개에서 끝 문장 누락을 해결하고 설치 가능한 수정 APK와 미서명 release 빌드를 준비했다. 정식 v0.1.0 릴리즈 완료·게시 수락은 아직 아니다.**

## 원인과 변경

사용자는 1번 원음에 대본 전체가 있다고 직접 확인했다. 전체 PCM·WAV는 이미 보존되고 있었다. SDK에 원음 전체를 보내 첫 완료 뒤8초간 관측해도 첫 구절 외 후속 결과가 없었다. 같은 원음의 뒤 두 발화를 쉼 구간에서 나눠 각각 입력하자 빠졌던 문장들이 반환됐다. 따라서 이번 데이터의 누락은 전체 음성 저장 실패가 아니라 단일 발화에서 종료되는 인식 처리와 연결된다.

공식 [외부 PCM 전송 가이드](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/sending-audio-data)는 ON_DEVICE의 전체 버퍼/청크 전송을 지원한다. [이벤트 문서](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/result-listeners)는 completed 결과와 종료 이벤트를 설명한다. 전체 버퍼 전송이 지원된다는 사실을 여러 발화 전체 전사 보장으로 해석하지 않았다.

- 원음은 그대로 저장하고, 내부 조용한 구간에서 샘플 경계로 나눠 동일 SDK에 순차 입력한다. 모든 바이트는 한 구간씩 포함되며 누락·겹침·원음 편집이 없다.
- 전체 구간이 성공하면 실제 인식 결과를 순서대로 한 메모에 저장한다. 중간 구간의 오류/빈 결과/timeout을 부분 성공으로 저장하지 않는다.
- 이전 구간의 늦은 콜백은 세대로 구분해 무시한다. 기존 캡처15초/전체20초 제한과 USER/UNKNOWN 취소·원음 보존은 유지한다.
- 완료된 SDK 구간은 listener 해제와 release만 수행한다. 미완료·취소에는 stop도 유지한다. SDK bytecode에서 stop이 비동기 service close를 예약하는 것을 확인했고, 후속 구간에 영향을 줄 수 있는 불필요한 stop을 제거했다.
- 제품 변경은 `PcmSpeechInput.kt`, `PcmUtteranceRanges.kt`, `AndroidPcmRecognitionEngine.kt`, `PcmEngineCleanup.kt`뿐이다. backend·공유 UI·DB schema·AI 정책은 변경하지 않았다.

## 실제 재확인

원본 PCM3개를 새 배치에 각1회 입력했다. 원문 전사 전체는 local-only `.local/stage8-user-audio/fixed-02/results.md`와 각 JSON에 있다. 공유 문서/소스/APK에는 사용자 전사를 넣지 않는다.

| 녹음 | 분리 구간 | 실제 결과 | 날짜·보존 |
| --- | ---: | --- | --- |
| 1 | 3 | 날짜와 두 경험 문장 모두 반환; 제공된 대본 내용과 일치 | 2026-11-08 / 원음·텍스트·DB재열기 동일 |
| 2 | 2 | 뒤쪽 경험 문장까지 반환; 제공된 대본 내용과 일치 | 2026-11-08 / 원음·텍스트·DB재열기 동일 |
| 3 | 3 | 마지막 문장까지 반환; 대본 대비 추가 단어는 남아 있음 | 2026-11-08 / 원음·텍스트·DB재열기 동일 |

기기 전달/저장 PCM은 각각338560 / 285312 / 330368바이트다. 구간별 전송 바이트 합계가 전체와 같고, 저장 WAV SHA는 변환 원본과 각각 같다. 직접 청취는 사용자 확인과 구분하며, 3번의 대본 대비 차이가 발화인지 오인식인지 단정하지 않는다. 날짜는 원래 m4a 촬영시각이 아니라 실제 인식 문장과 시험 입력 시각으로 해석했다.

**중간 실패 보존:** 첫 구간 처리 수정본 `fixed-01`의1번은25초 timeout이었다. 시험에 메타데이터 추적만 추가한 `fixed-trace-01`은 동일 제품에서3구간 전체 성공이었다. timeout의 정확한 발생 구간/원인은 확정하지 못했다. 수명 관리 보완 후 `fixed-02`의3회에서는 timeout이 없었으며 이 결과로 모든 간헐 장애가 해결됐다고 주장하지 않는다.

## 검증 근거

- TDD: 기존 처리에서 다중 발화6개가 첫 결과만 저장하는 등의 이유로 실패했고 수정 뒤 통과했다. 구간 분할5개·완료 후 stop 회귀2개도 의도한 실패를 확인했다. 초기 PARKED 시험 fixture와 미구현 심볼 오류는 별도 초기화 실패로 보존했다.
- 최종 `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease` exit0, 3분16초. **JVM340개, failures/errors/skipped0**. Lint 오류0, 경고36/힌트2.
- 기기 실제 녹음3회: 각각 `OK (1 test)`, source/input/APK 해시 고정. 호스트 전체시간2.579 / 1.915 / 2.454초. 이 값은 마이크 녹음 시간이나 일반 성능 보장이 아니다.
- 기존 기기 회귀: `CapturePersistenceTest`, `CaptureReviewContentTest`, `CaptureSyntheticSpeechProbeTest` **6/6 PASS**, JUnit2.172초. 기존 단일발화·저장·검토 화면을 포함한다.
- debug와 release DEX 로그 보호: 각 vendor log 호출0, SDK caller Android log 호출0, 결과 callback1.
- root가 변경 diff·입력 범위·취소/늦은 콜백·중간 실패·SDK 해제·원음 보존을 별도 자체 검토했다. 사용자 지시에 따라 다른 검증 AI를 호출하지 않았고 독립 리뷰라고 주장하지 않는다.
- 전체 Android 기기 suite·실제 마이크·정상 런처부터 공유까지의 통합 리허설·실API는 이번 수정에서 반복하지 않았다. 기존 AI REVIEW 및 내비 실제 도착/일반 알림센터/UI 간헐 이슈 수락 경계는 유지한다.

로그: `.local/verification/stage8-stt-{segments-red-02,segments-green-01,cleanup-red-01,full-build-02}.log`. 원시 기기 근거·source freeze·APK·보고서는 `.local/stage8-user-audio/fixed-02/`에 권한 제한 상태로 보존한다. 최초 실패와 진단 배치는 삭제/덮어쓰지 않는다.

## 실행 가능한 결과물

같은 서명의 수정 제품을 기존 전용 Pleos AVD 사용자10에 데이터 보존 업데이트했다. 제품 SHA256 `acf053d2494e64a16f4cd5b419a2d09fa65aeed369fed8695ea45a20db0e5b12`, 시험 APK `efd9cb51bdc60030c882a3231a2292f1bb8f753bc1bb665404dd7d615a040784`.

로컬 전달 폴더 `.local/release-candidates/stt-fix-20261115/`:

- `memory-vault-demo.apk`: 설치 가능한 debug 서명 수정본. 위 실제 기기 검사 대상이다.
- `memory-vault-release-unsigned.apk`: release 변형 빌드. SHA256 `9613c663ffc1c0a5ab7bdb0e8d482d47edd1b0cae122c662fb8d3ca6fdbe20dd`. 미서명이므로 그대로 설치/배포할 수 없다. localhost HTTP가 허용되지 않는 release 설정이며 실제 AI 서버 연결까지 확인한 것이 아니다.
- `README.txt`, `SHA256SUMS`: 실행 방법과 검증 범위/남은 조건.

앱 버전 메타데이터는 기존 `0.0.1-bootstrap`이다. 이 결과물을 정식 v0.1.0으로 표시하지 않는다. 기존 앱 목록·녹음 패널로 확인하고 실제 발화는 사용자가 준비된 시점에 [TESTING](../TESTING.md)의 주행/마이크 절차를 따른다. 이번 자동시험은 루프백 PCM과 격리DB를 사용했으며 원음과 시험 메모는 일반 앱 기록에 섞지 않았다.

## 남은 릴리즈 조건

1. 수정된 전체 전사로 기억 연결 정상 흐름을 재확인하고, 기존 REVIEW/부분 제안·복수 사진 정책의 수락 범위를 결정한다. 사용자 원음 클라우드 전송은 계속 미승인이다.
2. 앱 버전·배포 서명·실제 서버 연결 환경을 정식 릴리즈 기준으로 확정하고 해당 최종 산출물을 검증한다. 미서명 release 빌드 성공만으로 배포 가능 판정을 하지 않는다.
3. 내비 실제 도착/일반 알림센터/기존 UI 간헐 이슈와 STT 대본 대비 차이 및 잡음 환경의 한계를 명시한다. 고잡음·매우 짧거나 약한 발화까지 완전 전사 보장은 없다.
4. 최종 main 통합·인수인계·비밀정보 점검 뒤 정확한 원격/커밋/릴리즈 범위에 대한 승인된 게시만 수행한다. 이번 작업에서는 merge/push/태그/Release/촬영/P1/P2를 실행하지 않았다.

## 판단 기록

- Ruling: 기존8단계 feature에서 root가 직접 수정·검증한다 — 사용자 최신 총괄 직접 수행 지시와 릴리즈 준비 요청 — 자체 검토는 독립 검토와 같은 증거가 아니다.
- Ruling: 원음 전체 저장과 STT 구간 입력을 구분한다 — 첫 구절 후8초 추가결과가 없고 뒤쪽 구간 별도 인식은 성공했다 — 조용한 구간 분할을 모든 음성환경의 완전 전사로 확대하면 위험하다.
- Ruling: 완료 결과 뒤 불필요한 비동기 stop을 생략한다 — 종료된 구간을 다시 닫는 예약이 후속 요청과 경합할 수 있다 — 앞선 timeout 원인은 미확정이며 최종3회 성공으로 모든 간헐 장애 해결을 선언하지 않는다.
