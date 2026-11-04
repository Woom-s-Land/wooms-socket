# 패키지별 요청 흐름

먼저 세 가지 주요 흐름을 왼쪽에서 오른쪽으로 읽는다. 아래 그림의 화살표는 처리 순서다.

![연결, 일반 메시지, 권한 철회의 세 가지 요청 흐름](assets/request-flows-overview.png)

권한 확인은 최초 접근·캐시 만료뿐 아니라 주기 재검증 때도 수행한다.
이하 Mermaid 다이어그램은 저장·실패 처리까지 포함하는 상세 참조다.

2026-11-03 작업본 기준. Mermaid를 지원하는 Markdown 뷰어와 GitHub에서 다이어그램을 볼 수 있다.
각 노드의 패키지는 별도 표기가 없으면 `com.ee06.mychat.realtime` 하위다.
화살표는 호출·데이터 흐름을 나타내며 같은 실행 스레드라는 뜻은 아니다.

## 1. 전체 구조

```mermaid
flowchart TD
    Client["브라우저 · NettyClient"]
    Config["config · SocketProperties"]
    Server["websocket · RealtimeServer<br/>HTTP Upgrade · WebSocket 프레임"]
    Hub["websocket · NativeHub<br/>명령 해석 · 권한 확인 · 라우팅"]
    Session["session · SocketSession<br/>사용자/방 인증 캐시 · 연결 상태"]
    Bridge["backend · BackendBridge<br/>비동기 HTTP"]
    Backend["Spring Backend<br/>JWT · 방 가입 권한 · DB"]
    Queue["room · RoomQueue<br/>방별 순서 제어"]
    Presence["presence · PresenceService<br/>채팅 · 캐릭터"]
    Sketch["sketch · SketchService<br/>공동 그림판"]
    Mafia["mafia · MafiaGameEngine<br/>게임 규칙 · 타이머"]
    Bus["session · OutboundBus<br/>방송 · 개인 전달 · 쓰기 전 차단 확인"]
    Redis["Redis Pub/Sub"]
    Access["access.redis → access.port<br/>철회 이벤트 검사 · 처리 호출"]

    Config -. "설정" .-> Server
    Config -. "설정" .-> Hub
    Config -. "설정" .-> Bridge
    Client -->|"연결 · JSON 명령"| Server --> Hub
    Hub <-->|"캐시 · 상태"| Session
    Hub -->|"최초 접근 · 캐시 만료 · 주기 검증"| Bridge
    Bridge <-->|"인증 · 그림 조회/저장"| Backend
    Hub -->|"권한 확인 후"| Queue
    Queue --> Presence
    Queue --> Sketch
    Queue --> Mafia
    Sketch --> Bridge
    Presence --> Bus
    Sketch --> Bus
    Mafia --> Bus
    Bus -->|"각 연결 event loop에서 MESSAGE 전송"| Client
    Backend -->|"DB 커밋 후 권한 철회 발행"| Redis
    Redis --> Access -->|"ROOM_ACCESS_REVOKED"| Hub
    Hub -->|"즉시 차단 · 종료 조율"| Session
```

`config`는 시작 시 설정을 공급한다. 일반 메시지마다 거치는 요청 처리 단계는 아니다.
Redis는 권한 철회 전파에 사용하며 채팅·그림·게임 메시지의 전달 경로에는 들어가지 않는다.

## 2. 연결과 최초 방 구독

```mermaid
sequenceDiagram
    participant C as 브라우저
    participant W as websocket.RealtimeServer
    participant H as websocket.NativeHub
    participant S as session.SocketSession / OutboundBus
    participant B as backend.BackendBridge
    participant API as Spring Backend
    participant Q as room.RoomQueue
    participant F as presence / sketch

    C->>W: HTTP Upgrade 요청 + 로그인 Cookie
    W->>W: Origin 확인 · WebSocket handshake
    W-->>C: HTTP 101 Switching Protocols
    W->>H: open(channel, cookie)
    H->>S: 세션 생성
    W-->>C: CONNECTED
    Note over C,H: CONNECTED는 방 접근 인증 성공을 뜻하지 않음
    C->>W: SUBSCRIBE + 방 목적지
    W->>H: JSON 명령 전달
    H->>H: 허용 목적지 · 방 UUID 검사
    H->>B: 최초 방 권한 검증
    B->>API: GET /api/realtime/rooms/{roomId}/session + Cookie
    API->>API: JWT 검증 · ACCEPT 가입 상태 · 사용자 조회
    alt 검증 성공
        API-->>B: 사용자 UUID · 닉네임 · 의상 · expiresAt
        B-->>H: Identity
        H->>S: 방 권한 캐시 · 만료/재검증 예약
        H->>Q: 방 구독 설치 작업
        Q->>S: 구독 등록
        opt 이동 또는 그림 구독
            Q->>F: 초기 캐릭터 상태 또는 그림 스냅샷 준비
            Note over F,API: 그림 최초 로드는 backend HTTP 조회를 포함할 수 있음
            F->>S: 개인 초기 상태 전달
            S-->>C: MESSAGE
        end
    else 권한 거부 또는 검증 장애
        API-->>B: 오류
        B-->>H: 검증 실패
        H->>S: 즉시 차단 · 종료 정리
        H->>S: 오류 안내 · WebSocket 종료 요청
        S-->>C: ERROR + Close
    end
```

초기 상태용 개인 큐는 방 구독보다 먼저 구독해야 초기 응답을 받을 수 있다.
개인 큐 구독 자체가 방 데이터 접근을 승인하지는 않는다.

## 3. 채팅·이동·게임 메시지

```mermaid
flowchart TD
    C["브라우저 · SEND"] --> W["websocket.RealtimeServer"]
    W --> H["websocket.NativeHub<br/>목적지 · 본문 검사"]
    H --> Check{"해당 방의 유효한<br/>권한 캐시가 있는가?"}
    Check -->|"예"| Q["room.RoomQueue"]
    Check -->|"아니오"| B["backend.BackendBridge → Spring Backend"]
    B -->|"검증 성공 · 캐시 갱신"| Q
    B -->|"검증 실패"| Close["세션 차단 · 연결 종료"]
    Q --> Active{"세션이 여전히 활성인가?"}
    Active -->|"아니오"| Drop["명령 적용 중단"]
    Active -->|"예 · chat/move"| P["presence.PresenceService"]
    Active -->|"예 · sketchmafia"| M["mafia.engine.MafiaGameEngine"]
    P --> Bus["session.OutboundBus"]
    M --> Bus
    Bus --> Loop["수신 연결 event loop<br/>차단 상태 · 구독 재확인"]
    Loop --> Receivers["같은 방 구독자 또는 지정된 개인 연결"]
```

유효한 캐시를 사용하는 이벤트는 backend를 매번 호출하지 않는다.
동기 상태 변경과 권한 철회는 세션 monitor를 기준으로 순서를 정한다.
게임 타이머도 방 큐를 통해 상태 전환을 처리한다. 개인 역할은 방 전체에 방송하지 않는다.

## 4. 그림 입력·주기 저장·갤러리 보관

아래 흐름은 `NativeHub`의 권한 확인이 끝난 이후부터다.

```mermaid
sequenceDiagram
    participant H as websocket.NativeHub
    participant Q as room.RoomQueue
    participant SK as sketch.SketchService
    participant B as backend.BackendBridge
    participant API as Spring Backend / DB
    participant S as session.OutboundBus
    participant C as 방 구독자

    H->>Q: SEND sketch/draw
    Q->>SK: 그림 입력 처리
    opt 메모리에 그림이 없는 경우
        SK->>B: wall(roomId, cookie)
        B->>API: GET 방 그림
        API-->>SK: 현재 pixels (BackendBridge 경유)
    end
    SK->>SK: 세션 재확인 · 좌표/도구/색 검사 · 메모리 변경 · dirty
    SK->>S: stroke 방송
    S-->>C: MESSAGE

    Note over Q,API: 변경된 그림은 5초 주기로 저장 작업 예약
    SK->>Q: dirty 그림 flush 작업
    Q->>SK: 스냅샷 생성
    SK->>B: putWall(pixels)
    B->>API: 내부 PUT + 서버 전용 저장 키
    alt 저장 성공
        API-->>SK: 저장 완료 (BackendBridge 경유)
        SK->>SK: dirty 해제
    else 저장 실패
        API-->>SK: 오류 (BackendBridge 경유)
        SK->>SK: dirty 유지 · 이후 주기에서 재시도
    end

    H->>Q: SEND sketch/save
    Q->>SK: 갤러리 보관
    SK->>SK: 세션 재확인 · 빈 그림 거부 · 스냅샷
    SK->>B: archive(pixels, cookie)
    B->>API: POST wall/archive + 사용자 Cookie
    alt 보관 성공
        API-->>SK: 보관 완료 (BackendBridge 경유)
        SK->>SK: 캔버스 초기화
        SK->>S: RESET 방송
        S-->>C: MESSAGE
    else 보관 실패
        API-->>SK: 오류 (BackendBridge 경유)
        SK->>SK: 캔버스 유지
    end
```

DB 응답을 네트워크 event loop에서 동기 대기하지 않는다.
다만 방 큐는 해당 비동기 작업 완료를 기다리므로 같은 방의 다음 작업은 지연될 수 있다.
보관 요청이 이미 승인되어 시작됐다면 이후 연결이 철회돼도 성공 처리는 끝내 저장 상태와 메모리를 일치시킨다.

## 5. 탈퇴·권한 철회 전파

`Backend access.*`는 `com.ee06.wooms.domain.realtime.access`를 의미한다.

```mermaid
sequenceDiagram
    participant C as 탈퇴 요청 사용자
    participant WS as Backend WoomsService
    participant DB as DB Transaction
    participant P as Backend access.port / EventAdapter
    participant R as Backend access.redis relay
    participant Redis as Redis Pub/Sub
    participant A as Netty access.redis listener
    participant H as websocket.NativeHub / access.port handler
    participant S as session.SocketSession / OutboundBus
    participant Q as room / presence / mafia

    C->>WS: 방 탈퇴 요청
    WS->>DB: ACCEPT → REFUSE
    WS->>P: 도메인 이벤트 발생
    alt 트랜잭션 커밋
        DB-->>R: AFTER_COMMIT 콜백
        R->>R: 제한된 비동기 발행 큐
        WS-->>C: 탈퇴 응답
        R->>Redis: ROOM_ACCESS_REVOKED
        Note over C,Redis: 탈퇴 응답과 Redis 전달의 완료 순서는 보장하지 않음
        Redis-->>A: 각 구독 중인 Netty에 방송
        A->>A: JSON 계약 검사 · 중복 ID 확인
        A->>H: 처리 인터페이스 호출
        H->>S: 대상 세션 즉시 차단
        H->>S: event loop에서 종료 진행
        H->>Q: 기존 작업 뒤에 퇴장 정리 예약
        Q->>Q: presence · mafia 참여 정리
        Q->>S: 구독 · 세션 정리
    else 롤백
        DB-->>WS: 실패
        Note over P,Redis: 외부 이벤트 미발행
    end
```

발행 장애가 이미 커밋된 DB 작업을 되돌리지는 않는다.
현재는 전체 WebSocket 종료 정책이며, 같은 연결의 다른 방도 재접속해야 한다.
인증 중인 연결은 이벤트 사용자 UUID를 해당 방 검증 시도에 기록하고 backend 응답의 신뢰 UUID와 비교한다. 타 사용자면 연결을 유지하고, 일치할 때만 차단한다. UUID 비교와 인증 future 완료는 같은 세션 monitor 안에서 직렬화한다.
데이터를 보내지 않고 구독만 유지하던 사용자도 차단한다.

## 6. 하트비트와 누락 보완 재검증

```mermaid
sequenceDiagram
    participant C as 브라우저
    participant W as websocket.RealtimeServer / NativeHub
    participant S as session.SocketSession
    participant B as backend.BackendBridge
    participant API as Spring Backend

    loop 클라이언트 기본 10초 간격
        C->>W: JSON PING
        W-->>C: JSON PONG
    end
    Note over C,W: 하트비트는 연결 생존 확인이며 권한 검증이 아님
    loop 세션별 30~60초 분산 간격
        W->>S: 확인할 방 목록 · 재검증 예약 상태
        W->>B: 방별 권한 재검증
        B->>API: session API + Cookie
        alt 성공
            API-->>W: Identity (BackendBridge 경유)
            W->>S: 캐시 갱신 · 다음 검사 예약
        else 401/403 등 확정 거부
            API-->>W: 접근 거부 (BackendBridge 경유)
            W->>S: 즉시 송수신 차단 · 4403 종료
        else 5xx 또는 timeout 등 일시 장애
            B-->>W: 검증 일시 불가
            W->>S: 즉시 송수신 차단 · 1013 종료
        end
    end
    Note over W,S: 토큰 expiresAt 도달 시 별도 만료 작업으로 연결 종료
```

Redis 이벤트 유실 시에도 수신 전용 연결을 검사한다.
주기 재검증은 유실을 보완하지만 즉시 철회를 보장하지 않으며 요청 시간과 스케줄링 지연이 추가될 수 있다.
2026-11-04 실제 Redis 전달 통합에서 탈퇴 후 CLOSE 4403 / `room access revoked`와 신규 연결 거부를 확인했다. 브라우저 QA는 미검증이며 이 다이어그램은 코드상 처리 경로를 설명한다.
