# Exception과 ErrorCode 사용 안내

비즈니스 예외는 `GlobalException`을 상속하고, 오류의 HTTP 상태·코드·기본 메시지는 `ErrorCode`에서 관리합니다. `GlobalExceptionHandler`가 예외를 표준 API 응답으로 변환하므로 서비스와 컨트롤러에서 별도 `try-catch`를 작성하지 않습니다.

## 공통 ErrorCode

| ErrorCode | HTTP 상태 | code | 기본 메시지 |
|---|---:|---|---|
| `INVALID_INPUT` | 400 | `INVALID_INPUT` | 요청 값이 올바르지 않습니다. |
| `INVALID_PARAMETER` | 400 | `INVALID_PARAMETER` | 요청 파라미터가 올바르지 않습니다. |
| `INVALID_REQUEST_BODY` | 400 | `INVALID_REQUEST_BODY` | 요청 본문을 읽을 수 없습니다. |
| `RESOURCE_NOT_FOUND` | 404 | `RESOURCE_NOT_FOUND` | 요청한 정보를 찾을 수 없습니다. |
| `INTERNAL_SERVER_ERROR` | 500 | `INTERNAL_SERVER_ERROR` | 서버 오류가 발생했습니다. |

## 도메인 오류 코드 추가

사용자와 채팅 도메인에 필요한 코드는 `ErrorCode`에 추가합니다.

```java
USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다."),
EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", "이미 사용 중인 이메일입니다."),
CHAT_NOT_FOUND(HttpStatus.NOT_FOUND, "CHAT_NOT_FOUND", "채팅방을 찾을 수 없습니다."),
CHAT_ACCESS_DENIED(HttpStatus.FORBIDDEN, "CHAT_ACCESS_DENIED", "채팅방에 접근할 권한이 없습니다.")
```

## 도메인 예외 작성 예시

User 도메인 예외는 `UserException`을 작성합니다.

```java
public class UserException extends GlobalException {

    public UserException(ErrorCode errorCode) {
        super(errorCode);
    }
}
```

만약 채팅 도메인이라면 `ChatException`을 작성합니다.

```java
public class ChatException extends GlobalException {

    public ChatException(ErrorCode errorCode) {
        super(errorCode);
    }
}
```

## 서비스에서 사용 예시

사용자 조회와 이메일 중복 검증 예시입니다.

```java
User user = userRepository.findById(id)
        .orElseThrow(() -> new UserException(ErrorCode.USER_NOT_FOUND));

if (userRepository.existsByEmail(email)) {
    throw new UserException(ErrorCode.EMAIL_ALREADY_EXISTS);
}
```

채팅방 조회와 참여자 검증 예시입니다.

```java
ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
        .orElseThrow(() -> new ChatException(ErrorCode.CHAT_NOT_FOUND));

if (!chatRoom.isParticipant(userId)) {
    throw new ChatException(ErrorCode.CHAT_ACCESS_DENIED);
}
```

## 오류 응답 예시

`new UserException(ErrorCode.USER_NOT_FOUND)`가 발생하면 다음과 같이 응답합니다.

```json
{
  "success": false,
  "code": "USER_NOT_FOUND",
  "message": "사용자를 찾을 수 없습니다.",
  "data": null
}
```

## GlobalExceptionHandler 연결

`UserException`이 `GlobalException`을 상속하면, 현재 `GlobalExceptionHandler`에 이미 등록된 아래 핸들러가 자동으로 처리합니다. 따라서 기본 응답 형식이 같다면 `UserException`을 별도로 등록할 필요가 없습니다.

```java
@ExceptionHandler(GlobalException.class)
public ResponseEntity<ApiResponse<Void>> handleGlobalException(GlobalException exception) {
    ErrorCode errorCode = exception.getErrorCode();
    return ResponseEntity.status(errorCode.getStatus())
            .body(ApiResponse.error(errorCode.getCode(), exception.getMessage()));
}
```

특정 도메인만 별도 로그, 추가 데이터, 다른 메시지 정책이 필요하면 `GlobalExceptionHandler`에 더 구체적인 핸들러를 등록합니다. 예를 들어 이메일 중복 시 프론트가 어느 입력 필드를 표시해야 하는지 알 수 있도록 `data.field`를 추가하고, 사용자 도메인 오류를 별도 경고 로그로 남길 수 있습니다.

```java
@ExceptionHandler(UserException.class)
public ResponseEntity<ApiResponse<Map<String, String>>> handleUserException(UserException exception) {
    ErrorCode errorCode = exception.getErrorCode();
    log.warn("사용자 도메인 예외: code={}", errorCode.getCode());

    Map<String, String> details = switch (errorCode) {
        case EMAIL_ALREADY_EXISTS -> Map.of("field", "email");
        default -> null;
    };

    return ResponseEntity.status(errorCode.getStatus())
            .body(ApiResponse.error(errorCode.getCode(), exception.getMessage(), details));
}
```

위 핸들러로 `new UserException(ErrorCode.EMAIL_ALREADY_EXISTS)`를 처리하면 응답은 다음과 같습니다.

```json
{
  "success": false,
  "code": "EMAIL_ALREADY_EXISTS",
  "message": "이미 사용 중인 이메일입니다.",
  "data": {
    "field": "email"
  }
}
```

Spring은 `UserException` 전용 핸들러가 있으면 이를 우선 사용하고, 없으면 `GlobalException` 핸들러로 처리합니다. 추가 로그·데이터·메시지 정책이 없다면 전용 핸들러는 만들지 않고 공통 핸들러를 사용합니다.
