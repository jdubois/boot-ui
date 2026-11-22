<#
.SYNOPSIS
  BootUI CLI installer for Windows.

.DESCRIPTION
  Downloads the runnable `bootui` jar from Maven Central, checks it against the
  checksum Maven Central publishes beside it, and writes a `bootui` command that
  runs it. With GnuPG installed it also verifies the pinned release signature
  using the key served by the BootUI site. It needs no administrator rights.

.EXAMPLE
  irm https://www.julien-dubois.com/boot-ui/install.ps1 | iex

.EXAMPLE
  To pass options, run the downloaded script rather than piping it:

  & ([scriptblock]::Create((irm https://www.julien-dubois.com/boot-ui/install.ps1))) -NoPathUpdate
#>
[CmdletBinding()]
param(
  # Install this version instead of the newest release.
  [string] $Version = $env:BOOTUI_VERSION,
  # Where the jar goes.
  [string] $InstallDir = $env:BOOTUI_INSTALL_DIR,
  # Where the bootui command goes.
  [string] $BinDir = $env:BOOTUI_BIN_DIR,
  # Maven repository base URL.
  [string] $MavenRepo = $env:BOOTUI_MAVEN_REPO,
  # Leave the user PATH alone.
  [switch] $NoPathUpdate,
  # Remove what this script installed.
  [switch] $Uninstall,
  # Permit a missing checksum only after independent verification; never a mismatch.
  [switch] $AllowUnverified
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
# Invoke-WebRequest is dramatically slower while it draws a progress bar.
$ProgressPreference = 'SilentlyContinue'

$GroupPath = 'com/julien-dubois/bootui'
$Artifact = 'bootui-cli'
$ReleaseKeyFingerprint = '7B7C0BD038603E5A9F1476D0498BA5AC9BABBAF9'
$ReleaseKeyUrl = 'https://www.julien-dubois.com/boot-ui/bootui-release-key.asc'

if (-not $InstallDir) { $InstallDir = Join-Path $env:LOCALAPPDATA 'BootUI' }
if (-not $BinDir) { $BinDir = Join-Path $InstallDir 'bin' }
if (-not $MavenRepo) { $MavenRepo = 'https://repo1.maven.org/maven2' }
$MavenRepo = $MavenRepo.TrimEnd('/')

function Write-Step([string] $Message) { Write-Host $Message }

function Stop-Install([string] $Message) {
  # Write-Error would wrap this in a stack trace and fold the lines together.
  [Console]::Error.WriteLine("bootui install: $Message")
  exit 1
}

function Get-RemoteText([string] $Url) {
  # A repository serves checksum files as application/octet-stream, and for those
  # Invoke-WebRequest hands back raw bytes rather than a string.
  $content = (Invoke-WebRequest -Uri $Url -UseBasicParsing).Content
  if ($content -is [byte[]]) {
    return [Text.Encoding]::UTF8.GetString($content)
  }
  return [string] $content
}

function Invoke-NativeCommand([string] $Command, [string[]] $Arguments) {
  # Windows PowerShell 5.1 turns native stderr into error records even on success.
  # Keep that diagnostic handling local; callers still enforce the native status.
  $ErrorActionPreference = 'Continue'
  $output = & $Command @Arguments 2>&1
  $code = $LASTEXITCODE
  [pscustomobject] @{ ExitCode = $code; Output = $output }
}

function New-StagedInstallFile([string] $Path) {
  $prefix = Join-Path ([IO.Path]::GetDirectoryName($Path)) ('.bootui-' + [Guid]::NewGuid().ToString('N'))
  [pscustomobject] @{
    Path = $Path; Stage = "$prefix.tmp"; Backup = "$prefix.bak"
    HadOriginal = $false; Published = $false; KeepBackup = $false
  }
}

function Publish-InstallFile($File) {
  $File.HadOriginal = Test-Path -LiteralPath $File.Path
  if ($File.HadOriginal) {
    # On Windows even a failed ReplaceFileW can rename the original to the backup.
    [IO.File]::Replace($File.Stage, $File.Path, $File.Backup)
  } else {
    [IO.File]::Move($File.Stage, $File.Path)
  }
  $File.Published = $true
}

function Restore-InstallFile($File) {
  if ($File.Published -and -not $File.HadOriginal) {
    [IO.File]::Delete($File.Path)
  } elseif (Test-Path -LiteralPath $File.Backup) {
    if (-not (Test-Path -LiteralPath $File.Path)) {
      [IO.File]::Move($File.Backup, $File.Path)
    } elseif ($File.Published) {
      # Keep a backup even during rollback; the original was at $File.Backup.
      [IO.File]::Replace($File.Backup, $File.Path, $File.Stage)
    }
  } elseif ($File.Published) {
    throw "The previous file backup is missing: $($File.Backup)"
  }
}

function Remove-InstallScratchFile([string] $Path) {
  if ([IO.File]::Exists($Path)) {
    try {
      Remove-Item -LiteralPath $Path -Force
    } catch {
      Write-Warning "Could not remove installer scratch file ${Path}: $($_.Exception.Message)" -WarningAction Continue
    }
  }
}

# Windows PowerShell 5.1 may still default to TLS 1.0, which Maven Central refuses.
try {
  [Net.ServicePointManager]::SecurityProtocol =
    [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
} catch {
  # PowerShell 7 manages this itself and the type may be absent.
}

# ---------------------------------------------------------------- uninstall

if ($Uninstall) {
  $removed = $false
  $shim = Join-Path $BinDir 'bootui.cmd'
  if (Test-Path -LiteralPath $shim) {
    Remove-Item -LiteralPath $shim -Force
    Write-Step "Removed $shim"
    $removed = $true
  }
  # Only ever delete jars this installer wrote, never the directory blindly.
  if (Test-Path -LiteralPath $InstallDir) {
    Get-ChildItem -LiteralPath $InstallDir -Filter "$Artifact-*-all.jar" -File -ErrorAction SilentlyContinue |
      ForEach-Object {
        Remove-Item -LiteralPath $_.FullName -Force
        Write-Step "Removed $($_.FullName)"
        $script:removed = $true
      }
  }
  # What the CLI's update check left there.
  foreach ($stateFile in @('latest-version', 'update-checked-at', 'installation-v1', 'update.lock')) {
    Remove-Item -LiteralPath (Join-Path $InstallDir $stateFile) -Force -ErrorAction SilentlyContinue
  }
  if (-not $removed) { Write-Step 'Nothing to remove.' }
  Write-Step "If you added $BinDir to your PATH, remove it there too."
  exit 0
}

# ---------------------------------------------------------------- version

if (-not $Version) {
  Write-Step 'Asking Maven Central for the newest BootUI CLI release...'
  $metadataUrl = "$MavenRepo/$GroupPath/$Artifact/maven-metadata.xml"
  try {
    $metadata = [xml] (Get-RemoteText $metadataUrl)
  } catch {
    Stop-Install "could not read the repository metadata at`n  $metadataUrl`n  Check your network, or pass -Version."
  }
  $versioning = $metadata.metadata.versioning
  $Version = $versioning.release
  if (-not $Version) { $Version = $versioning.latest }
  if (-not $Version) { Stop-Install 'no released version is listed in the repository metadata.' }
}

# The version becomes part of a URL and of a file name, so hold it to what a version can be.
if ($Version -notmatch '^[0-9A-Za-z][0-9A-Za-z.+-]*$') {
  Stop-Install "'$Version' is not a usable version number."
}

$jarName = "$Artifact-$Version-all.jar"
$jarUrl = "$MavenRepo/$GroupPath/$Artifact/$Version/$jarName"

# ---------------------------------------------------------------- download

$tempDir = Join-Path ([IO.Path]::GetTempPath()) ("bootui-install-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tempDir -Force | Out-Null
try {
  $tempJar = Join-Path $tempDir $jarName

  Write-Step "Downloading BootUI CLI $Version..."
  try {
    Invoke-WebRequest -Uri $jarUrl -OutFile $tempJar -UseBasicParsing
  } catch {
    Stop-Install "could not download`n  $jarUrl`n  If $Version is the version you meant, it may not be published yet."
  }

  # ---------------------------------------------------------------- verify

  $verified = $false
  foreach ($algorithm in @('SHA512', 'SHA256', 'SHA1')) {
    $suffix = $algorithm.Replace('SHA', 'sha')
    try {
      $published = Get-RemoteText "$jarUrl.$suffix"
    } catch {
      continue
    }
    # A Maven checksum file holds a bare digest, sometimes followed by a file name.
    $expected = ($published -split '\r?\n')[0].Trim().Split(' ')[0].ToLowerInvariant()
    $length = @{ SHA512 = 128; SHA256 = 64; SHA1 = 40 }[$algorithm]
    if ($expected -notmatch "^[0-9a-f]{$length}$") {
      Stop-Install "malformed published $algorithm checksum. Nothing was installed."
    }
    $actual = (Get-FileHash -LiteralPath $tempJar -Algorithm $algorithm).Hash.ToLowerInvariant()
    if ($actual -ne $expected) {
      Stop-Install ("the download does not match the $algorithm checksum published beside it.`n" +
        "  expected $expected`n  got      $actual`n  Nothing was installed.")
    }
    Write-Step "Verification: published $algorithm checksum matched (repository integrity, not signer authentication)."
    if ($algorithm -eq 'SHA1') { Write-Step 'Warning: this older release supplies only the legacy SHA-1 checksum.' }
    $verified = $true
    break
  }
  if (-not $verified) {
    if (-not $AllowUnverified) {
      Stop-Install 'no checksum could be verified. Nothing was installed. Use -AllowUnverified only after independent verification.'
    }
    Write-Step 'Warning: checksum verification was explicitly bypassed with -AllowUnverified.'
  }

  $gpg = Get-Command gpg -ErrorAction SilentlyContinue
  if ($gpg) {
    $keyring = Join-Path $tempDir 'keyring'
    New-Item -ItemType Directory -Path $keyring | Out-Null
    $releaseKey = Join-Path $tempDir 'release-key.asc'
    $signature = Join-Path $tempDir 'cli.asc'
    Invoke-WebRequest -Uri $ReleaseKeyUrl -OutFile $releaseKey -UseBasicParsing
    Invoke-WebRequest -Uri "$jarUrl.asc" -OutFile $signature -UseBasicParsing
    $keyImport = Invoke-NativeCommand -Command $gpg.Source -Arguments @('--homedir', $keyring, '--batch', '--import', $releaseKey)
    if ($keyImport.ExitCode -ne 0) { Stop-Install 'could not import the release key.' }
    $verification = Invoke-NativeCommand -Command $gpg.Source -Arguments @('--homedir', $keyring, '--batch', '--status-fd', '1',
      '--verify', $signature, $tempJar)
    if ($verification.ExitCode -ne 0 -or
        -not ($verification.Output -match "^\[GNUPG:\] VALIDSIG .* $ReleaseKeyFingerprint$")) {
      Stop-Install 'invalid release signature or signer is not the pinned BootUI key. Nothing was installed.'
    }
    Write-Step "Verification: OpenPGP signature matched pinned BootUI key $ReleaseKeyFingerprint."
  } else {
    Write-Step 'Verification: signer not authenticated (GnuPG unavailable).'
  }

  # ---------------------------------------------------------------- install

  New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
  New-Item -ItemType Directory -Path $BinDir -Force | Out-Null
  $InstallDir = (Resolve-Path -LiteralPath $InstallDir).Path
  $BinDir = (Resolve-Path -LiteralPath $BinDir).Path

  $targetJar = Join-Path $InstallDir $jarName
  $shim = Join-Path $BinDir 'bootui.cmd'
  $record = Join-Path $InstallDir 'installation-v1'
  $jarFile = New-StagedInstallFile $targetJar
  $shimFile = New-StagedInstallFile $shim
  $recordFile = New-StagedInstallFile $record
  $rollbackOrder = @($shimFile, $recordFile, $jarFile)

  try {
    # Stage each file on its destination volume before touching the current install.
    [IO.File]::Copy($tempJar, $jarFile.Stage)
    $utf8 = New-Object Text.UTF8Encoding($false)
    [IO.File]::WriteAllText($recordFile.Stage, "bootui-install-v1`n$targetJar`n$shim`n$MavenRepo`n", $utf8)
    # Every `exit /b %ERRORLEVEL%` below is deliberately outside a parenthesised block:
    # inside one, cmd expands it when the block is parsed, so the CLI's exit code
    # (0, 1 or 2) would be replaced by a constant and CI gates would stop working.
    @"
@echo off
rem Generated by the BootUI CLI installer. Re-run the installer to update.
setlocal
set "BOOTUI_JAR=$targetJar"
rem Where the CLI keeps what its update check found. Only needed because this
rem install chose a directory; the CLI defaults to the same place on its own.
if not defined BOOTUI_INSTALL_DIR set "BOOTUI_INSTALL_DIR=$InstallDir"
if not defined BOOTUI_MAVEN_REPO set "BOOTUI_MAVEN_REPO=$MavenRepo"
if not defined JAVA_HOME goto bootui_path
if not exist "%JAVA_HOME%\bin\java.exe" goto bootui_path
"%JAVA_HOME%\bin\java.exe" -jar "%BOOTUI_JAR%" %*
exit /b %ERRORLEVEL%

:bootui_path
where java >nul 2>nul
if errorlevel 1 goto bootui_nojava
java -jar "%BOOTUI_JAR%" %*
exit /b %ERRORLEVEL%

:bootui_nojava
echo bootui: no Java runtime found. BootUI needs a JDK 17 or later. 1>&2
exit /b 1
"@ | Set-Content -LiteralPath $shimFile.Stage -Encoding ASCII

    Publish-InstallFile $jarFile
    Publish-InstallFile $recordFile
    # Switch the command only after its new jar and record are ready.
    Publish-InstallFile $shimFile
  } catch {
    $failure = $_.Exception.Message
    foreach ($file in $rollbackOrder) {
      try {
        Restore-InstallFile $file
      } catch {
        $file.KeepBackup = $true
        $failure += "`n  Could not restore $($file.Path): $($_.Exception.Message)"
        if ([IO.File]::Exists($file.Backup)) {
          $failure += "`n  Previous file retained at $($file.Backup)"
        }
      }
    }
    Stop-Install "could not complete installation:`n  $failure"
  } finally {
    foreach ($file in $rollbackOrder) {
      Remove-InstallScratchFile $file.Stage
      if (-not $file.KeepBackup) {
        Remove-InstallScratchFile $file.Backup
      }
    }
  }
} finally {
  Remove-Item -LiteralPath $tempDir -Recurse -Force -ErrorAction SilentlyContinue
}

# The install is committed. Cleanup and reporting must not turn it into a failed install.
try {
  $olderJars = Get-ChildItem -LiteralPath $InstallDir -Filter "$Artifact-*-all.jar" -File |
    Where-Object { $_.FullName -ne $targetJar }
  foreach ($previousJar in $olderJars) {
    try {
      Remove-Item -LiteralPath $previousJar.FullName -Force
      Write-Step "Removed the previous $($previousJar.Name)"
    } catch {
      Write-Warning ("Could not remove '$($previousJar.FullName)'. Close any process using it and remove it manually. " +
        $_.Exception.Message) -WarningAction Continue
    }
  }
} catch {
  Write-Warning ("Could not list older BootUI CLI JARs in '$InstallDir'. Remove obsolete versions manually; " +
    "keep '$targetJar'. $($_.Exception.Message)") -WarningAction Continue
}

Write-Step "Installed $jarName in $InstallDir"
Write-Step "Installed the bootui command at $shim"

# ---------------------------------------------------------------- report

# The jar cannot run without a JDK 17, but someone installing tooling may be about
# to install one, so say it plainly rather than refuse to finish.
try {
  $java = Get-Command java -CommandType Application -ErrorAction SilentlyContinue
  if ($java) {
    $javaVersion = Invoke-NativeCommand -Command $java.Source -Arguments @('-version')
    $versionLine = ($javaVersion.Output | Select-Object -First 1) -as [string]
    if ($javaVersion.ExitCode -ne 0 -or $versionLine -notmatch '(\d+)') {
      Write-Warning 'Could not determine the Java version. Run java -version to check for JDK 17 or later.' -WarningAction Continue
    } elseif ([int] $Matches[1] -lt 17) {
      Write-Step ''
      Write-Step "Warning: the java on your PATH is version $($Matches[1]). BootUI needs 17 or later."
    }
  } else {
    Write-Step ''
    Write-Step 'Warning: no java was found on your PATH. BootUI needs a JDK 17 or later to run.'
  }
} catch {
  Write-Warning ("Could not determine the Java version. Run java -version to check for JDK 17 or later. " +
    $_.Exception.Message) -WarningAction Continue
}

try {
  $isWindowsHost = -not (Test-Path variable:IsWindows) -or $IsWindows
  $updateUserPath = $isWindowsHost -and -not $NoPathUpdate
  $userPath = if ($updateUserPath) { [Environment]::GetEnvironmentVariable('Path', 'User') } else { $env:PATH }
  $separator = [IO.Path]::PathSeparator
  $onPath = ($userPath -split $separator) -contains $BinDir
  if (-not $onPath -and $updateUserPath) {
    $updated = if ($userPath) { "$($userPath.TrimEnd(';'));$BinDir" } else { $BinDir }
    [Environment]::SetEnvironmentVariable('Path', $updated, 'User')
    $env:Path = "$env:Path;$BinDir"
    Write-Step ''
    Write-Step "Added $BinDir to your user PATH. Open a new terminal for it to take effect elsewhere."
  } elseif (-not $onPath) {
    Write-Step ''
    Write-Step "$BinDir is not on your PATH. To use the command in this session:"
    Write-Step ''
    Write-Step "    `$env:Path = `"`$env:Path$separator$BinDir`""
  }
} catch {
  Write-Warning ("Could not finish checking or updating PATH. Check that '$BinDir' is on PATH, " +
    "or run '$shim' directly. $($_.Exception.Message)") -WarningAction Continue
}

Write-Step ''
Write-Step 'Done. Try it against a running application:'
Write-Step ''
Write-Step '    bootui --url http://localhost:8080 tools'
# Reflect the install outcome, not the status of the optional native Java probe.
$global:LASTEXITCODE = 0
