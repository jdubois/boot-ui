$ErrorActionPreference = "Stop"

$RepositoryUrl = if ($env:BOOTUI_REPO_URL) {
    $env:BOOTUI_REPO_URL
} else {
    "https://github.com/jdubois/boot-ui.git"
}

$TargetDirectory = if ($env:BOOTUI_DIR) {
    $env:BOOTUI_DIR
} else {
    "boot-ui"
}

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

if ((Test-Path -LiteralPath ".\mvnw.cmd") -and (Test-Path -LiteralPath ".\bootui-sample-app")) {
    Write-Host "Using existing BootUI checkout at $((Get-Location).Path)."
} else {
    Require-Command git

    if (Test-Path -LiteralPath $TargetDirectory) {
        throw "Target directory '$TargetDirectory' already exists. Run this script from that checkout, remove it, or set BOOTUI_DIR to another path."
    }

    Write-Host "Cloning BootUI into $TargetDirectory..."
    Invoke-Native -FilePath "git" -Arguments @("clone", "--depth", "1", $RepositoryUrl, $TargetDirectory)
    Set-Location -LiteralPath $TargetDirectory
}

Require-Command java
Require-Command docker

Write-Host "Building BootUI with tests skipped..."
Invoke-Native -FilePath ".\mvnw.cmd" -Arguments @("-B", "-ntp", "install", "-DskipTests")

Write-Host "Starting the BootUI sample app with PostgreSQL, Redis, and Ollama."
Write-Host "First startup may also pull the qwen2.5:0.5b chat model. Open http://localhost:8080/bootui after startup."
Invoke-Native -FilePath ".\mvnw.cmd" -Arguments @(
    "-pl",
    "bootui-sample-app",
    "spring-boot:run",
    "-Dspring-boot.run.profiles=dev"
)
