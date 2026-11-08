# 실시간 서버 패키지와 처리 흐름

2026-11-08 마피아 제외 작업본 기준. 기본 패키지는 `com.ee06.mychat.realtime`이다.
Spring Boot는 설정과 수명주기를 관리하고 Netty가 WebSocket 연결을 처리한다.
이 문서는 현재 구현의 책임과 경계를 설명하며 운영 성능 보장을 의미하지 않는다.

연결·일반 메시지·그림 저장·권한 철회·재검증의 다이어그램은 [패키지별 요청 흐름](request-flows.md)을 참고한다.

## 패키지 구성

```text
realtime/
├─ websocket/  연결 수락과 명령 라우팅
├─ session/    연결·구독 상태와 메시지 전달
├─ config/     실시간 서버 설정
├─ backend/    backend HTTP 연동
├─ room/       방별 작업 순서 제어
├─ presence/   채팅과 캐릭터 상태
├─ sketch/     공동 그림판
└─ access/     권한 철회 이벤트
```

| 패키지 | 주요 클래스 | 책임 |
|---|---|---|
| `websocket` | `RealtimeServer`, `NativeHub` | WebSocket 연결·Origin 검사·유휴 연결 정리, JSON 명령 해석, 목적지 검증, 구독·발행 라우팅, 인증·만료·종료 조율 |
| `session` | `SocketSession`, `OutboundBus` | 연결별 쿠키·인증 결과·구독·종료 상태 보관, 방 방송과 연결별 개인 메시지 전달, 실제 쓰기 전 차단 상태 확인 |
| `config` | `SocketProperties` | bind 주소·포트·경로, 허용 Origin, 프레임 크기, backend URL·timeout, 실행 스레드 수, 서비스 저장 키, 권한 캐시·재검증·이벤트 설정 |
| `backend` | `BackendBridge` | backend에 비동기 HTTP 요청. 방 접근 권한·사용자 정보 확인, 그림 조회·주기 저장·보관 요청과 응답 해석 |
| `room` | `RoomQueue` | 같은 방의 명령을 순서대로 실행하고 반환된 비동기 작업 완료까지 순서 유지. 고정 스레드를 여러 방이 공유하며 방별 대기 작업 수 제한 |
| `presence` | `PresenceService` | 서버에서 확정한 닉네임으로 채팅 방송, 캐릭터 위치·방향·의상·연결 소유권 관리, 입장 초기 상태와 퇴장 방송 |
| `sketch` | `SketchService` | 32×32 그림 상태, 펜·지우개 검증과 동기화, 입장 스냅샷, 변경분 주기 저장, 갤러리 보관 성공 후 초기화 |
| `access` | `RoomAccessRevoked`, `RoomAccessRevocationHandler`, Redis 구현 | backend의 권한 철회 이벤트를 받아 해당 연결의 송수신 차단 및 종료 처리로 연결 |

## 권한 철회 하위 패키지

| 패키지 | 주요 클래스 | 책임 |
|---|---|---|
| `access.event` | `RoomAccessRevoked` | 버전·이벤트 ID·방 UUID·사용자 UUID·사유·발생 시각의 메시지 계약 |
| `access.port` | `RoomAccessRevocationHandler` | 전송 수단과 무관한 권한 철회 처리 인터페이스. `NativeHub`가 구현 |
| `access.redis` | `RoomAccessRevocationListener`, `RoomAccessRevocationRedisConfiguration` | Redis 채널 구독, 역직렬화·계약 검사·제한된 중복 ID 기억, 처리 인터페이스 호출 |

backend의 대응 코드는 `com.ee06.wooms.domain.realtime.access`에 있다.
도메인에서는 발행 인터페이스를 사용하며, Spring 이벤트를 거쳐 DB 커밋 이후 Redis로 전파한다.
전송 수단을 교체할 때는 이벤트 계약과 처리 인터페이스를 유지하고 Redis 어댑터를 교체한다.

## 일반 메시지 흐름

1. `RealtimeServer`가 연결을 수락하고 `NativeHub`가 세션을 연다. `CONNECTED`는 방 접근 인증 성공을 의미하지 않는다.
2. `NativeHub`가 `SUBSCRIBE` 또는 `SEND`의 목적지와 본문을 검사한다.
3. 최초 방 접근이면 `BackendBridge`를 통해 로그인과 현재 가입 권한을 확인한다. 이후 유효한 세션 캐시를 사용한다.
4. 방 작업은 `RoomQueue`에서 순서대로 실행하고 `PresenceService`, `SketchService`으로 전달한다.
5. `OutboundBus`가 구독자를 찾아 각 연결의 event loop에서 차단·구독 상태를 확인한 뒤 메시지를 쓴다.

backend는 인증·가입 정보와 DB 영속 데이터의 소유자다. Netty는 실시간 연결과 메모리 상태를 담당한다.
HTTP 요청은 비동기지만 반환된 작업을 방 큐가 기다리므로 느린 저장은 같은 방의 후속 명령을 지연시킬 수 있다.

## 권한 철회 흐름

1. backend의 기존 탈퇴 경로가 가입 상태를 `ACCEPT`에서 `REFUSE`로 변경한다.
2. DB 커밋 성공 후 비동기 Redis 발행을 요청한다. 롤백 시 외부 이벤트는 발행하지 않는다.
3. Netty Redis 리스너가 이벤트를 검사하고 `RoomAccessRevocationHandler`를 호출한다.
4. `NativeHub`는 이미 검증된 사용자 UUID 또는 in-flight 검증이 완료해 신뢰된 UUID가 이벤트 대상과 일치할 때만 해당 세션을 차단한다. 같은 방에서 다른 사용자의 인증이 진행 중이면 응답 UUID를 확인할 때까지 연결을 유지한다.
5. 기존 종료 경로가 구독·캐릭터 상태를 정리한다. 수신만 하던 연결도 대상이다.

현재 정책은 방 구독 하나만 해제하는 대신 해당 WebSocket 전체를 종료한다.
같은 연결로 이용 중인 다른 방도 영향을 받으며 재접속 시 backend 검증을 다시 거친다.
철회 전에 이미 송신 큐에 들어간 프레임은 회수할 수 없다.
철회 전에 승인되어 시작된 그림 보관 HTTP가 성공하면 캔버스 초기화도 완료해 저장 상태와 메모리를 일치시킨다.

## 연결과 권한 수명 정책

- 클라이언트는 기본 10초마다 JSON `PING`을 보내고 서버는 `PONG`으로 응답한다.
- 클라이언트는 30초 넘게 서버 메시지가 없으면 연결을 닫고, 활성 상태이면 기본 5초 뒤 재접속한다. 서버는 60초 수신 유휴 시 종료한다.
- 최초 방 권한은 반드시 검증하고 `expiresAt`에 맞춰 연결을 종료한다.
- 권한 캐시는 기본 60초이며 주기 재검증은 세션별 30~60초로 분산한다. 수신 전용 연결도 포함한다.
- 확정적인 권한 거부는 4403, backend timeout·5xx 등 일시적인 검증 장애는 1013으로 구분해 연결을 종료한다.
- Redis Pub/Sub에는 영속 보관·재생 보장이 없고 Outbox도 구현하지 않았다. 유실된 철회는 주기 재검증으로 보완하므로 반영이 재검증 간격과 요청 처리 시간만큼 지연될 수 있다.

## 경계와 검증 범위

- STOMP와 Netty에서 같은 방을 동시에 운영하거나 여러 Netty 인스턴스가 그림 상태를 공유하는 기능은 없다. Redis 권한 이벤트 방송이 도메인 상태 공유를 제공하지는 않는다.
- 채팅 이력 재생과 서버 재시작 후 캐릭터 위치 등 휘발성 상태 복구는 지원하지 않는다.
- 방 큐·그림 캐시의 미사용 상태 정리와 구독 해제의 방 퇴장 의미 정리는 후속 개선 대상이다.
- 이전 버전의 테스트 통과 기록은 현재 커밋의 재검증 결과를 의미하지 않는다. 2026-11-04 실제 Redis 통합에서 close code 4403 / reason `room access revoked` 및 신규 연결 권한 거부를 확인했다. 브라우저 화면 QA는 미완료다.
