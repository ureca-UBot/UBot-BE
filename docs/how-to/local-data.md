# 로컬 테스트 데이터 준비하기

> 문서 기준: UBot-BE `develop` [`37bc033`](https://github.com/ureca-UBot/UBot-BE/commit/37bc033a1439c23cf5c586d8acb4455fb0130be2) (2026-09-29 15:01 KST 커밋, #104 병합 시점) · 작성일 2026-09-29

마이그레이션은 매장 임시 데이터(`V5`)만 넣습니다. 관리자 계정과 FAQ는 비어 있으므로, 채팅 답변까지 확인하려면 아래 순서로 직접 준비해야 합니다.

전제: [quickstart](../quickstart.md) 6단계까지 완료되어 애플리케이션이 `http://localhost:8080`에서 실행 중이고, Compose의 Ollama에 `bge-m3:567m`이 받아져 있어야 합니다. FAQ를 등록할 때마다 서버가 질문을 임베딩하기 때문입니다.

## 1. 관리자 계정

1. 회원가입 API(`POST /auth/signup`)나 프론트 회원가입 화면으로 계정을 만듭니다.
2. DB에서 그 계정의 역할을 `ADMIN`으로 바꿉니다. SQL은 [db-access.md](db-access.md#로컬에서-관리자-계정-만들기)에 있습니다.
3. 다시 로그인합니다. 관리자 API는 바로 쓸 수 있지만, 프론트 화면은 토큰의 `role`을 보므로 새로 로그인해야 관리자 화면이 열립니다.

## 2. FAQ 등록

FAQ는 SQL로 직접 넣지 말고 관리자 API로 등록합니다. API가 질문 임베딩을 `faq.vector`에 함께 저장합니다. SQL로 넣은 행은 벡터가 비어 채팅 검색에 쓰이지 않습니다.

몇 건만 필요하면 Swagger UI(`/swagger-ui.html`)에서 `POST /admin/faq-categories`로 카테고리를 만든 뒤 `POST /admin/faqs`로 등록하면 됩니다.

### CSV로 한 번에 등록하기

팀의 FAQ 원본은 이 저장소에 없습니다. FAQ 담당에게 받아 아래 형식의 UTF-8 CSV로 만듭니다.

```csv
category,question,answer,intent
유심,유심을 재발급받으려면 어떻게 하나요?,가까운 매장을 방문하거나 …,GENERAL
매장,가까운 매장은 어디인가요?,…,STORE_DATA
```

| 열 | 규칙 |
|---|---|
| `category` | 카테고리 이름(100자 이하). 없으면 스크립트가 새로 만듭니다 |
| `question`, `answer` | 공백이 아닌 1000자 이하 |
| `intent` | `GENERAL`, `STORE_DATA`, `USER_DATA` 중 하나. 비우면 `GENERAL` |

`src/test/resources/threshold/faq_intent_corpus.csv`는 threshold 측정용 질문 목록이라 답변 열이 없어 이 용도로 쓸 수 없습니다.

아래 스크립트를 저장소 밖에 `load-faqs.ps1`로 저장하고 실행합니다. Windows PowerShell 5.1과 PowerShell 7에서 모두 동작하도록 작성했습니다.

```powershell
param(
    [Parameter(Mandatory)] [string] $CsvPath,
    [Parameter(Mandatory)] [string] $Email,
    [string] $BaseUrl = 'http://localhost:8080'
)

$securePassword = Read-Host '관리자 비밀번호' -AsSecureString
$password = (New-Object System.Net.NetworkCredential('', $securePassword)).Password
$script:AccessToken = $null

# 응답을 UTF-8로 직접 읽어 한글이 깨지지 않게 하고, 실패 응답도 ApiResponse로 돌려줍니다.
function Invoke-Api([string] $Method, [string] $Path, $Body) {
    $headers = @{}
    if ($script:AccessToken) { $headers.Authorization = "Bearer $script:AccessToken" }
    $params = @{
        Method          = $Method
        Uri             = "$BaseUrl$Path"
        Headers         = $headers
        ContentType     = 'application/json; charset=utf-8'
        UseBasicParsing = $true
    }
    if ($null -ne $Body) {
        $params.Body = [System.Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 5))
    }
    try {
        $response = Invoke-WebRequest @params
        $text = [System.Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())
        return $text | ConvertFrom-Json
    } catch {
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) { return $_.ErrorDetails.Message | ConvertFrom-Json }
        throw
    }
}

function Connect-Admin {
    $script:AccessToken = $null
    $result = Invoke-Api 'POST' '/auth/login' @{ email = $Email; password = $password }
    if (-not $result.success) { throw "로그인 실패: $($result.code)" }
    $script:AccessToken = $result.data.accessToken
}

# access token이 만료되면(JWT-007) 다시 로그인해 한 번 더 보냅니다.
function Invoke-AdminApi([string] $Method, [string] $Path, $Body) {
    $result = Invoke-Api $Method $Path $Body
    if (-not $result.success -and $result.code -eq 'JWT-007') {
        Connect-Admin
        $result = Invoke-Api $Method $Path $Body
    }
    return $result
}

Connect-Admin

# 기존 카테고리 이름 → ID
$categoryIds = @{}
$page = 0
do {
    $result = Invoke-AdminApi 'GET' "/admin/faq-categories?page=$page&size=100" $null
    if (-not $result.success) { throw "카테고리 조회 실패: $($result.code)" }
    foreach ($category in $result.data.content) { $categoryIds[$category.name] = $category.faqCategoryId }
    $page++
} while (-not $result.data.last)

$rows = Import-Csv -Path $CsvPath -Encoding UTF8
$succeeded = 0
$failures = @()
foreach ($row in $rows) {
    if (-not $categoryIds.ContainsKey($row.category)) {
        $result = Invoke-AdminApi 'POST' '/admin/faq-categories' @{ name = $row.category }
        if (-not $result.success) { throw "카테고리 생성 실패 ($($row.category)): $($result.code)" }
        $categoryIds[$row.category] = $result.data.faqCategoryId
    }

    $intent = if ($row.intent) { $row.intent } else { 'GENERAL' }
    $result = Invoke-AdminApi 'POST' '/admin/faqs' @{
        categoryId = $categoryIds[$row.category]
        question   = $row.question
        answer     = $row.answer
        intent     = $intent
    }
    if ($result.success) {
        $succeeded++
        Write-Host "[$succeeded/$($rows.Count)] $($row.question)"
    } else {
        $failures += "$($result.code)  $($row.question)"
    }
}

Write-Host "등록 $succeeded 건, 실패 $($failures.Count) 건"
$failures | ForEach-Object { Write-Host $_ }
```

```powershell
.\load-faqs.ps1 -CsvPath .\faqs.csv -Email admin@example.com
```

- 한 건마다 임베딩을 계산하므로 수백 건이면 몇 분 걸릴 수 있습니다.
- 같은 CSV를 두 번 실행하면 FAQ가 중복 등록됩니다. 서버에 중복 FAQ 검사가 없습니다.
- 실패 목록의 코드는 [오류 코드 Reference](../reference/error-codes.md)에서 확인합니다. 흔한 경우는 `G-001`(1000자 초과·빈 값), `EM-001`/`EM-003`(Ollama 미실행)입니다.

## 3. 채팅 모델

채팅 답변까지 확인하려면 Ollama에 채팅 모델을 받고 `.env`의 `OLLAMA_CHAT_MODEL`에 지정합니다([quickstart 4단계](../quickstart.md#4-embedding-모델-준비-확인)). UBot-FE의 채팅 연동 확인에는 `qwen3:14b`를 사용했습니다. 큰 모델이라 GPU가 없으면 응답이 `LLM_READ_TIMEOUT`(기본 120초)을 넘길 수 있습니다.

## 4. 확인

1. 로그인해서 받은 access token으로 `POST /chat/questions`에 등록한 FAQ와 비슷한 질문을 보냅니다.
2. 실패하면 `code`를 보고 [troubleshooting](../troubleshooting.md#채팅-요청이-실패함)을 따릅니다. 유사도가 기준(`CHAT_CONFIDENCE_THRESHOLD`, 기본 0.75)에 못 미치면 `CHAT-013`입니다.

금지어 필터를 확인하려면 관리자 API `POST /admin/forbidden-words`로 금지어를 등록한 뒤 그 단어가 들어간 질문을 보냅니다(`FW-003`).
