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

# Build the list of selectable refs: "main" followed by up to the 5 most recent tags.
# The default selection is the most recent tag when one exists, otherwise "main".
function Select-Ref {
    param([string[]] $Tags = @())

    $options = @("main") + $Tags
    $defaultIndex = if ($Tags.Count -gt 0) { 1 } else { 0 }

    if ($env:BOOTUI_REF) {
        Write-Host "Using BOOTUI_REF=$($env:BOOTUI_REF)."
        return $env:BOOTUI_REF
    }

    Write-Host "Select the BootUI version to build and run:"
    for ($i = 0; $i -lt $options.Count; $i++) {
        $suffix = if ($i -eq $defaultIndex) { " (default)" } else { "" }
        Write-Host ("  {0}) {1}{2}" -f ($i + 1), $options[$i], $suffix)
    }

    while ($true) {
        $choice = Read-Host ("Enter a number [{0}]" -f ($defaultIndex + 1))
        if ([string]::IsNullOrWhiteSpace($choice)) {
            return $options[$defaultIndex]
        }
        $number = 0
        if ([int]::TryParse($choice, [ref] $number) -and $number -ge 1 -and $number -le $options.Count) {
            return $options[$number - 1]
        }
        Write-Host ("Invalid selection. Please enter a number between 1 and {0}." -f $options.Count)
    }
}

if ((Test-Path -LiteralPath ".\mvnw.cmd") -and (Test-Path -LiteralPath ".\bootui-sample-app")) {
    Write-Host "Using existing BootUI checkout at $((Get-Location).Path)."
    Require-Command git

    $tags = @(& git tag --sort=-v:refname 2>$null | Select-Object -First 5)
    $selectedRef = Select-Ref -Tags $tags

    Write-Host "Checking out '$selectedRef'..."
    Invoke-Native -FilePath "git" -Arguments @("checkout", $selectedRef)
} else {
    Require-Command git

    if (Test-Path -LiteralPath $TargetDirectory) {
        throw "Target directory '$TargetDirectory' already exists. Run this script from that checkout, remove it, or set BOOTUI_DIR to another path."
    }

    $tags = @(& git ls-remote --tags --refs --sort=-v:refname $RepositoryUrl 2>$null |
        ForEach-Object { ($_ -replace '.*refs/tags/', '') } |
        Select-Object -First 5)
    $selectedRef = Select-Ref -Tags $tags

    Write-Host "Cloning BootUI ($selectedRef) into $TargetDirectory..."
    Invoke-Native -FilePath "git" -Arguments @("clone", "--depth", "1", "--branch", $selectedRef, $RepositoryUrl, $TargetDirectory)
    Set-Location -LiteralPath $TargetDirectory
}

Require-Command java

Write-Host "Building BootUI with tests skipped..."
Invoke-Native -FilePath ".\mvnw.cmd" -Arguments @("-B", "-ntp", "install", "-DskipTests")

Write-Host "Starting the BootUI sample application."
Write-Host "Open http://localhost:8080/bootui after startup."
Invoke-Native -FilePath ".\mvnw.cmd" -Arguments @(
    "-pl",
    "bootui-sample-app",
    "spring-boot:run",
    "-Dspring-boot.run.profiles=dev"
)
