param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$Target,

    [Parameter(Mandatory = $true, Position = 1)]
    [string]$Command,

    [Parameter(Position = 2, ValueFromRemainingArguments = $true)]
    [string[]]$ExtraArgs
)

$ErrorActionPreference = "Stop"

$AwsProfile = "ureca-terraform"
$AwsRegion = "ap-northeast-2"
$TerraformImage = if ($env:TF_DOCKER_IMAGE) {
    $env:TF_DOCKER_IMAGE
}
else {
    "ubot-terraform:1.16.4"
}

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$TerraformHostPath = Join-Path $RepoRoot "infra\terraform\$Target"
$TerraformContainerPath = "/workspace/infra/terraform/$Target"
$AwsDir = Join-Path $HOME ".aws"

if (-not (Test-Path $TerraformHostPath)) {
    Write-Error "Terraform target을 찾을 수 없습니다: $TerraformHostPath"
    exit 1
}

if (-not (Test-Path $AwsDir)) {
    Write-Error "AWS 설정 폴더를 찾을 수 없습니다: $AwsDir"
    exit 1
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Error "Docker를 찾을 수 없습니다."
    exit 1
}

# Terraform + AWS CLI가 포함된 로컬 이미지 확인
$TerraformImageId = docker image ls `
    --quiet `
    --filter "reference=$TerraformImage"

if ([string]::IsNullOrWhiteSpace($TerraformImageId)) {
    Write-Host ""
    Write-Host "Terraform Docker 이미지를 찾을 수 없습니다: $TerraformImage"
    Write-Host ""
    Write-Host "먼저 다음 명령으로 이미지를 빌드하세요:"
    Write-Host "docker build -t ubot-terraform:1.16.4 .\tools\terraform"
    Write-Host ""
    exit 1
}

$DockerArgs = @(
    "run"
    "--rm"
    "-it"

    "--mount"
    "type=bind,source=$RepoRoot,target=/workspace"

    # aws login cache를 갱신할 수 있도록 read-only로 마운트하지 않음
    "--mount"
    "type=bind,source=$AwsDir,target=/root/.aws"

    "-w"
    $TerraformContainerPath

    "--env"
    "AWS_PROFILE=$AwsProfile"

    "--env"
    "AWS_REGION=$AwsRegion"

    "--env"
    "AWS_DEFAULT_REGION=$AwsRegion"

    "--env"
    "AWS_EC2_METADATA_DISABLED=true"

    $TerraformImage
    $Command
)

if ($ExtraArgs) {
    $DockerArgs += $ExtraArgs
}

Write-Host ""
Write-Host "Terraform target : $Target"
Write-Host "Terraform command: $Command"
Write-Host "AWS profile      : $AwsProfile"
Write-Host "AWS region       : $AwsRegion"
Write-Host "Docker image     : $TerraformImage"
Write-Host ""

& docker @DockerArgs

exit $LASTEXITCODE