param(
    [Parameter(Mandatory = $true)]
    [string]$Email,

    [System.Security.SecureString]$Password,

    [string]$BaseUrl = "http://localhost:8080",

    [int]$TimeoutSec = 3600
)

$ErrorActionPreference = "Stop"

# Messages are kept in ASCII for Windows PowerShell 5.1 compatibility.

$MaxAttempts = 3

if (-not $Password) {
    $Password = Read-Host "Admin password" -AsSecureString
}

$PlainPassword = (New-Object System.Net.NetworkCredential("", $Password)).Password


# Returns the ApiResponse object for both success and error responses.
function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        $Body = $null,
        [string]$Token = $null,
        [int]$RequestTimeoutSec = 30
    )

    $parameters = @{
        Method          = $Method
        Uri             = "$BaseUrl$Path"
        ContentType     = "application/json; charset=utf-8"
        TimeoutSec      = $RequestTimeoutSec
        UseBasicParsing = $true
    }

    if ($Token) {
        $parameters.Headers = @{ Authorization = "Bearer $Token" }
    }

    if ($null -ne $Body) {
        $parameters.Body = [System.Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json))
    }

    try {
        $response = Invoke-WebRequest @parameters
        $text = [System.Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())

        return $text | ConvertFrom-Json
    }
    catch {
        # PowerShell 7 fills ErrorDetails with the body. Windows PowerShell 5.1 leaves it empty, so read the stream.
        $text = $null

        if ($_.ErrorDetails) {
            $text = $_.ErrorDetails.Message
        }

        if (-not $text -and $_.Exception.Response -is [System.Net.HttpWebResponse]) {
            $reader = New-Object System.IO.StreamReader(
                $_.Exception.Response.GetResponseStream(),
                [System.Text.Encoding]::UTF8
            )

            try {
                $text = $reader.ReadToEnd()
            }
            finally {
                $reader.Dispose()
            }
        }

        $result = $null

        if ($text) {
            try {
                $result = $text | ConvertFrom-Json
            }
            catch {
            }
        }

        if ($result -and $result.code) {
            return $result
        }

        throw "No usable response from $BaseUrl$Path. Is the backend running? ($($_.Exception.Message))"
    }
}


function Get-AccessToken {
    $result = Invoke-Api `
        -Method "POST" `
        -Path "/auth/login" `
        -Body @{ email = $Email; password = $PlainPassword }

    if (-not $result.success) {
        throw "Login failed: $($result.code)"
    }

    return $result.data.accessToken
}


function Invoke-Backfill {
    param(
        [string]$Name,
        [string]$Path
    )

    for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
        # A long backfill can outlive the access token, so log in before every call.
        $token = Get-AccessToken

        Write-Host ""
        Write-Host "Backfilling $Name embeddings... (this can take several minutes)"

        $result = Invoke-Api `
            -Method "POST" `
            -Path $Path `
            -Token $token `
            -RequestTimeoutSec $TimeoutSec

        if ($result.success) {
            Write-Host "Done: $Name, newly embedded = $($result.data)"
            return
        }

        # Vectors saved before the failure are kept, so a retry continues from there.
        if ($result.code -like "EM-*" -and $attempt -lt $MaxAttempts) {
            Write-Host "Embedding server error ($($result.code)). Retrying ($($attempt + 1)/$MaxAttempts)..."
            continue
        }

        if ($result.code -eq "AUTH-002") {
            throw "$Name backfill failed: AUTH-002. The account must have the ADMIN role."
        }

        throw "$Name backfill failed: $($result.code)"
    }
}


Write-Host ""
Write-Host "Target: $BaseUrl"

Invoke-Backfill `
    -Name "FAQ" `
    -Path "/admin/faqs/embeddings/backfill"

Invoke-Backfill `
    -Name "unanswered question" `
    -Path "/admin/unanswered-groups/embeddings/backfill"

Write-Host ""
Write-Host "Embedding backfill finished."
