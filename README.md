# WOOMS Socket

기존 `develop`의 Netty 작업을 기반으로 WOOMS 실시간 기능을 별도 프로세스에서 실행한다.
Spring Boot는 구성과 수명주기를 관리하며, 브라우저 연결은 Netty WebSocket이 직접 처리한다.
기존 줄 단위 TCP 구현은 소스에 보존하지만 애플리케이션 스캔에서 제외했다.

로컬 작업 브랜치는 socket의 `feat/wooms-websocket-migration`, backend/frontend의 `feat/netty-transport-integration`이다.
운영 배포와 대규모 성능 비교는 별도 작업이다.

## 실행

Java 22가 필요하다. 먼저 `wooms-backend`의 `feat/netty-transport-integration` 작업본을 실행한다.
Netty는 backend의 인증·그룹 권한·그림 저장 API를 사용한다. Redis나 JWT 공유 비밀을 새로 설정할 필요는 없다.
두 서버 프로세스에 동일한 `WOOMS_REALTIME_INTERNAL_SECRET` 환경변수를 설정해야 한다.
이 값은 32자 이상의 무작위 서버 전용 키이며, frontend의 `VITE_` 변수에 넣지 않는다.
Netty는 미설정·너무 짧은 키로 시작하지 않고, backend 내부 저장 API도 미설정·틀린 키를 거부한다.

```powershell
.\gradlew.bat bootJar
java -jar build/libs/mychat-0.0.1-SNAPSHOT.jar
```

실제 JAR 이름은 `build/libs/`에서 확인한다. 기본 주소는 `ws://localhost:8090/ws`이며 backend는 `http://localhost:8080`이다.
설정은 `wooms.socket.host`, `port`, `path`, `backend-url`, `allowed-origins`, `max-frame-bytes`, `room-threads`, `backend-timeout`으로 변경한다.
기본 bind는 `127.0.0.1`이다. 외부 운영 배포·TLS·프록시 설정은 이번 작업 범위에 포함하지 않는다.

frontend에서 다음 환경변수로 실행한다. 로그인과 일반 REST 요청은 기존 backend를 사용한다.

```powershell
$env:VITE_SOCKET_TRANSPORT = 'netty'
$env:VITE_NETTY_WS_URL = 'ws://localhost:8090/ws'
npm run dev
```

기존 STOMP를 사용하려면 `VITE_SOCKET_TRANSPORT=stomp`로 실행하거나 환경변수를 제거한다.
`VITE_STOMP_WS_URL` 기본값은 `ws://localhost:8080/ws`다. 한 frontend 실행에서 선택한 전송 방식만 활성화한다.
Vite 환경변수 변경 후 개발 서버를 다시 시작해야 한다.

## 프로토콜과 기능

STOMP 프레임 대신 JSON 이벤트를 사용한다. 기존 화면을 재사용하기 위해 목적지 이름과 메시지 본문 형식을 유지했다.

```json
{"op":"SUBSCRIBE","id":"sub-1","destination":"/ws/wooms/chat/방UUID"}
{"op":"SEND","destination":"/ws/send/chat/방UUID","body":"{\"content\":\"hello\"}"}
{"op":"UNSUBSCRIBE","id":"sub-1"}
{"op":"PING"}
```

서버는 `CONNECTED`, `MESSAGE`, `ERROR`, `PONG` 이벤트를 반환한다.
`MESSAGE`에는 `subscription`, `destination`, JSON 문자열인 `body`가 들어간다.
지원 기능은 채팅·캐릭터 이동 및 초기 상태/퇴장, 공동 그림판의 초기 스냅샷/그리기/보관, 그림 마피아의 참가/시작/제출/토론/투표/정답 추측이다.
마피아 규칙·점수·제한시간과 topic 데이터는 backend 구현을 이식했다.

## 인증과 상태 관리

- WebSocket handshake의 `Authorization` 쿠키를 backend로 전달해 해당 방의 `ACCEPT` 가입 상태를 검증한다. 브라우저는 동일 호스트의 쿠키를 기본 전송한다. localhost와 127.0.0.1을 섞으면 쿠키가 전달되지 않을 수 있다.
- room ID는 숫자 그룹 ID가 아닌 초대 코드 UUID다. 닉네임·의상·사용자 식별자는 backend가 반환한 DB 정보로 확정한다.
- 토큰 만료 시 연결을 종료하고, 수신만 하는 연결도 5초 간격으로 방 권한을 다시 확인한다. 명령 처리의 권한 캐시도 최대 5초다. 가입 상태 변경의 전파에는 확인 주기와 backend 응답 시간만큼 지연이 있다. 재연결에는 유효한 로그인 쿠키가 필요하다.
- private role/error/init은 해당 연결의 구독으로만 전달한다. 방 구독과 명령은 승인된 목적지만 허용한다.
- 마피아 재참가 소유권은 사용자 UUID로 확인하며, 같은 닉네임의 다른 사용자가 기존 역할을 받을 수 없다.
- 방별 실행 큐가 상태 변경·방송·비동기 저장 순서를 직렬화한다. 네트워크 I/O 이벤트 루프에서 DB나 HTTP 완료를 기다리지 않는다.
- 그림 데이터와 갤러리는 기존 backend DB가 소유한다. Netty는 메모리에서 그린 후 주기적으로 저장하고, 보관이 성공한 뒤에만 캔버스를 초기화한다.
- 이미 승인한 그림의 주기 저장은 서버 전용 자격을 사용하므로 사용자 쿠키 만료와 분리된다. 새 그림 입력은 연결 권한 검증을 통과해야 한다. 일반 사용자 조회·보관 API의 그룹 권한 검증은 유지한다.
- 같은 방을 STOMP와 Netty에서 동시에 운영하는 방식과 다중 Netty 인스턴스 간 상태 공유는 지원하지 않는다. 비교 시 서로 다른 테스트 방을 사용하거나 서버를 교대로 실행한다.
- 송신 버퍼 한계를 넘는 느린 연결은 종료한다. 재연결 시 구독과 초기 상태를 복원한다. 오프라인 메시지 이력 재생 기능은 없다.

## 검증과 비교

```powershell
.\gradlew.bat test
```

frontend 어댑터 테스트는 frontend 폴더의 `node --test tests/NettyClient.test.mjs`로 실행한다.
실제 backend를 사용하는 통합 검증은 별도 메모리 DB와 독립 포트를 사용한다. 기존 `data/devdb`를 테스트 대상으로 사용하지 않는다.

2026-11-02 검증 기록:

- backend의 그룹 권한·그림 저장·내부 서비스 키 테스트와 기존 STOMP 마피아 라운드 테스트 통과.
- socket의 애플리케이션 실행, 마피아 UUID 소유권, 인증 만료·권한 재검증·연결 종료 경쟁, 서비스 자격 저장 테스트 통과.
- frontend 어댑터 테스트 7개와 STOMP/Netty 두 모드 빌드 통과.
- 실제 backend와 Netty를 별도 JVM으로 실행해 로그인 쿠키 기반 통합 검증 11개 항목 통과. 채팅·이동·그림판·갤러리·주기 저장·두 방 격리·미가입/익명/Origin 거부·3인 마피아 라운드·퇴장 정리를 확인했다.
- 통합 검증 도구와 결과는 backend의 `tools/netty-smoke.py`, `tools/netty-smoke-result.json`에 있다. Python `websocket-client`와 `WOOMS_TEST_PASSWORD` 환경변수가 필요하며, 테스트용 seed 계정과 메모리 DB를 전제로 한다.
- 독립 정적 리뷰 승인. 브라우저 화면 수동 QA, 운영 배포, 다중 인스턴스, 대규모 부하 비교는 수행하지 않았다.

성능 비교는 후속 작업이다. 기존 STOMP의 1,000연결 기준선은 backend의 `tools/stomp-benchmark/README.md`에 보존했다.
이전 기준선은 익명 chat 경로이므로 인증이 필수인 현재 Netty 결과와 그대로 비교하면 안 된다.
비교할 때는 인증 조건, 메시지 내용과 바이트 수, 방 구성, 전송률, 로그 수준, 워밍업과 측정 시간, CPU/메모리 제한을 맞춘다.
Netty의 backend 인증·저장 비용도 측정 범위에 명시하며, JSON 방식과 향후 바이너리 최적화의 효과를 구분한다.
