# Exception과 ErrorCode 사용 안내

비즈니스 예외는 `GlobalException`을 상속하고, 오류의 HTTP 상태·코드·기본 메시지는 도메인별 `ErrorCode` 구현체에서 관리합니다. `GlobalExceptionHandler`가 예외를 표준 API 응답으로 변환하므로 서비스와 컨트롤러에서 별도 `try-catch`를 작성하지 않습니다.

## ErrorCode는 도메인별로 분리합니다

`ErrorCode`는 하나의 공통 파일이 아니라, HTTP 상태·코드·메시지를 정의하는 **인터페이스**입니다. Java의 enum은 클래스를 상속할 수 없으므로, 도메인마다 `ErrorCode`를 구현하는 enum을 하나씩 둡니다. 이렇게 하면 도메인끼리 같은 파일을 수정하면서 코드가 충돌하는 일이 없습니다.

```java
public interface ErrorCode {
    HttpStatus getStatus();
    String getCode();
    String getMessage();
}
```

공통(도메인에 속하지 않는) 오류는 `CommonErrorCode`에서 관리합니다.

| ErrorCode | HTTP 상태 | code | 기본 메시지 |
|---|---:|---|---|
| `INVALID_INPUT` | 400 | `G-001` | 요청 값이 올바르지 않습니다. |
| `INVALID_PARAMETER` | 400 | `G-002` | 요청 파라미터가 올바르지 않습니다. |
| `INVALID_REQUEST_BODY` | 400 | `G-003` | 요청 본문을 읽을 수 없습니다. |
| `RESOURCE_NOT_FOUND` | 404 | `G-004` | 요청한 정보를 찾을 수 없습니다. |
| `INTERNAL_SERVER_ERROR` | 500 | `G-005` | 서버 오류가 발생했습니다. |

## 도메인 오류 코드 추가

도메인 패키지(`com.ubot.<도메인>.exception`) 아래에 `<도메인>ErrorCode`를 추가합니다. 코드 문자열은 도메인을 나타내는 대문자 접두사와 일련번호를 `-`로 연결한 `PREFIX-번호` 형식을 사용합니다(예: `USER-001`, `FAQ-001`, `STORE-001`).

```java
package com.ubot.user.exception;

@Getter
@RequiredArgsConstructor
public enum UserErrorCode implements ErrorCode {
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER-001", "사용자를 찾을 수 없습니다."),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "USER-003", "이미 사용 중인 이메일입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
```

## 도메인 예외 작성 예시

도메인 예외도 같은 패키지(`com.ubot.<도메인>.exception`)에 `<도메인>Exception` 하나만 둡니다. 같은 도메인 안에서 상황별로 예외 클래스를 여러 개 만들지 않고, 하나의 예외 클래스가 도메인 `ErrorCode`를 인자로 받도록 합니다.

```java
package com.ubot.user.exception;

public class UserException extends GlobalException {

    public UserException(UserErrorCode errorCode) {
        super(errorCode);
    }

    public UserException(UserErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
```

## 서비스에서 사용 예시

사용자 조회와 이메일 중복 검증 예시입니다.

```java
User user = userRepository.findById(id)
        .orElseThrow(() -> new UserException(UserErrorCode.USER_NOT_FOUND));

if (userRepository.existsByEmail(email)) {
    throw new UserException(UserErrorCode.EMAIL_ALREADY_EXISTS);
}
```

## 오류 응답 예시

`new UserException(UserErrorCode.USER_NOT_FOUND)`가 발생하면 다음과 같이 응답합니다.

```json
{
  "success": false,
  "code": "USER-001",
  "message": "사용자를 찾을 수 없습니다.",
  "data": null
}
```

## GlobalExceptionHandler 연결

도메인 예외가 `GlobalException`을 상속하면, `GlobalExceptionHandler`에 이미 등록된 아래 핸들러가 자동으로 처리합니다. `ErrorCode`가 인터페이스이므로 어떤 도메인의 구현체든 동일하게 동작합니다. 따라서 기본 응답 형식이 같다면 도메인 예외를 별도로 등록할 필요가 없습니다.

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

    Map<String, String> details = errorCode == UserErrorCode.EMAIL_ALREADY_EXISTS
            ? Map.of("field", "email")
            : null;

    return ResponseEntity.status(errorCode.getStatus())
            .body(ApiResponse.error(errorCode.getCode(), exception.getMessage(), details));
}
```

위 핸들러로 `new UserException(UserErrorCode.EMAIL_ALREADY_EXISTS)`를 처리하면 응답은 다음과 같습니다.

```json
{
  "success": false,
  "code": "USER-003",
  "message": "이미 사용 중인 이메일입니다.",
  "data": {
    "field": "email"
  }
}
```

Spring은 `UserException` 전용 핸들러가 있으면 이를 우선 사용하고, 없으면 `GlobalException` 핸들러로 처리합니다. 추가 로그·데이터·메시지 정책이 없다면 전용 핸들러는 만들지 않고 공통 핸들러를 사용합니다.

## 요약

- `ErrorCode`는 인터페이스이며, 도메인별로 `<도메인>ErrorCode` enum을 만들어 구현합니다.
- 예외 클래스도 도메인별로 `<도메인>Exception` 하나만 두고, 같은 패키지(`com.ubot.<도메인>.exception`)에 둡니다.
- 코드 문자열은 `PREFIX-번호` 형식으로 통일합니다.
- 도메인에 속하지 않는 공통 오류는 `CommonErrorCode`를 사용합니다.
