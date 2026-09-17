# ApiResponse 사용 안내

모든 API는 `ApiResponse<T>`로 같은 JSON 구조를 반환합니다. 오류 응답의 코드와 예외 처리 방법은 [exception.md](exception.md)를 참고하세요.

## 응답 형식

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {}
}
```

| 필드 | 설명 |
|---|---|
| `success` | 요청 처리 성공 여부입니다. |
| `code` | 클라이언트가 응답 종류를 구분하는 애플리케이션 코드입니다. |
| `message` | 사용자 또는 개발자에게 보여줄 수 있는 설명입니다. |
| `data` | 성공 데이터입니다. 없으면 `null`입니다. |

`code`는 HTTP 상태 코드와 구분합니다. 예를 들어 입력값 오류는 HTTP `400`과 `INVALID_INPUT`을 함께 반환합니다.

## 기본 성공 응답

컨트롤러에서 정적 팩토리 메서드를 사용합니다.

```java
@GetMapping("/users/{id}")
public ResponseEntity<ApiResponse<UserResponse>> getUser(@PathVariable Long id) {
    UserResponse user = userService.findById(id);
    return ResponseEntity.ok(ApiResponse.success(user));
}
```

응답은 다음과 같습니다.

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "id": 1,
    "name": "홍길동"
  }
}
```

## 성공 메시지 변경

기본 메시지 대신 API 목적에 맞는 메시지를 반환할 수 있습니다.

```java
return ResponseEntity.ok(ApiResponse.success("사용자 정보를 조회했습니다.", user));
```

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "사용자 정보를 조회했습니다.",
  "data": {
    "id": 1,
    "name": "홍길동"
  }
}
```

## 실패 응답

실패해도 `ApiResponse`의 필드 구조는 동일합니다. `success`는 `false`이고, `code`에는 오류 종류를, `message`에는 오류 설명을 담습니다. 일반적인 도메인 오류에서는 `data`가 `null`입니다.

```json
{
  "success": false,
  "code": "USER_NOT_FOUND",
  "message": "사용자를 찾을 수 없습니다.",
  "data": null
}
```

입력값 검증에 실패하면 `data`에 필드별 오류 메시지가 포함됩니다.

```json
{
  "success": false,
  "code": "INVALID_INPUT",
  "message": "요청 값이 올바르지 않습니다.",
  "data": {
    "email": "이메일 형식이 아닙니다.",
    "password": "비밀번호는 8자 이상이어야 합니다."
  }
}
```

오류 코드의 정의와 `UserException`, `ChatException`처럼 도메인 예외를 작성하는 방법은 [exception.md](exception.md)를 참고하세요.

## 사용 원칙

- 컨트롤러는 성공한 데이터만 `ApiResponse.success(...)`로 감쌉니다.
- 오류는 컨트롤러에서 `try-catch`로 직접 응답하지 않고 예외를 발생시킵니다.
- 발생한 예외는 `GlobalExceptionHandler`가 같은 응답 구조로 변환합니다.
