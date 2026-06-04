$ErrorActionPreference = "Stop"

function Require-Command {
    param([Parameter(Mandatory = $true)][string] $Name)

    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "'$Name' is required to run the BootUI sample app."
    }
}

function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string] $FilePath,
        [string[]] $Arguments = @()
    )

    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}

function Get-StableHash {
    param([Parameter(Mandatory = $true)][string] $Value)

    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value)
        $hash = $sha256.ComputeHash($bytes)
        return [BitConverter]::ToUInt32($hash, 0)
    } finally {
        $sha256.Dispose()
    }
}

function Resolve-Port {
    param(
        [Parameter(Mandatory = $true)][string] $Name,
        [string] $Value,
        [Parameter(Mandatory = $true)][int] $Default
    )

    if ([string]::IsNullOrWhiteSpace($Value)) {
        return $Default
    }

    [int] $parsed = 0
    if (-not [int]::TryParse($Value, [ref] $parsed) -or $parsed -lt 1 -or $parsed -gt 65535) {
        throw "$Name must be a TCP port between 1 and 65535."
    }

    return $parsed
}

$ScriptDirectory = Split-Path -Parent $PSCommandPath
$RootDirectory = (Resolve-Path -LiteralPath (Join-Path $ScriptDirectory "..")).Path
$MavenWrapper = Join-Path $RootDirectory "mvnw.cmd"
$SampleAppDirectory = Join-Path $RootDirectory "bootui-sample-app"
$MavenRepository = if ($env:BOOTUI_MAVEN_REPO) {
    $env:BOOTUI_MAVEN_REPO
} else {
    Join-Path $RootDirectory ".m2\repository"
}

if (-not (Test-Path -LiteralPath $MavenWrapper) -or -not (Test-Path -LiteralPath $SampleAppDirectory)) {
    throw "This script must live in the scripts directory of a BootUI checkout."
}

Require-Command java
Require-Command docker

$hash = Get-StableHash -Value $RootDirectory
$AppPort = Resolve-Port -Name "BOOTUI_PORT" -Value $env:BOOTUI_PORT -Default (10000 + ($hash % 10000))
$OllamaPort = Resolve-Port -Name "BOOTUI_OLLAMA_PORT" -Value $env:BOOTUI_OLLAMA_PORT -Default (30000 + ($hash % 10000))
$ComposeProjectName = if ($env:BOOTUI_COMPOSE_PROJECT_NAME) {
    $env:BOOTUI_COMPOSE_PROJECT_NAME
} elseif ($env:COMPOSE_PROJECT_NAME) {
    $env:COMPOSE_PROJECT_NAME
} else {
    "bootui-$hash"
}

$env:BOOTUI_OLLAMA_PORT = [string] $OllamaPort
$env:COMPOSE_PROJECT_NAME = $ComposeProjectName

Set-Location -LiteralPath $RootDirectory

Write-Host "Using Maven repository: $MavenRepository"
Write-Host "Using Docker Compose project: $ComposeProjectName"
Write-Host "Using Ollama port: $OllamaPort"
Write-Host "Building BootUI with tests skipped..."

Invoke-Native -FilePath $MavenWrapper -Arguments @(
    "-B",
    "-ntp",
    "-Dmaven.repo.local=$MavenRepository",
    "-DskipTests",
    "install"
)

Write-Host "Starting the BootUI sample app at http://localhost:$AppPort/bootui"

Invoke-Native -FilePath $MavenWrapper -Arguments @(
    "-B",
    "-ntp",
    "-Dmaven.repo.local=$MavenRepository",
    "-pl",
    "bootui-sample-app",
    "spring-boot:run",
    "-Dspring-boot.run.profiles=dev",
    "-Dspring-boot.run.arguments=--server.port=$AppPort --spring.ai.ollama.base-url=http://localhost:$OllamaPort"
)
