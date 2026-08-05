<#
.SYNOPSIS
    Pulls the latest Cyberpunk 2027 sources, builds the mod, and installs it into Minecraft.

.DESCRIPTION
    One command for the edit-elsewhere / play-here loop: fetch, fast-forward, build, verify,
    install. Every step is checked, and the script stops before touching the mods folder if
    anything is wrong, so a failed build can never leave a stale or half-copied jar behind.

    The build is deliberately not silent about the two things that actually go wrong on this
    project: the NeoForm runtime asks for a Java 21 toolchain even though the mod targets 25,
    and a dirty working tree makes `git pull` fail in a confusing way.

.PARAMETER Repo
    Repository checkout to build. Defaults to the directory containing this script's parent.

.PARAMETER ModsDir
    Minecraft mods directory. Defaults to %APPDATA%\.minecraft\mods.

.PARAMETER JdkHome
    JDK to build with. Auto-detected if omitted.

.PARAMETER SkipPull
    Build what is already checked out instead of fetching first.

.PARAMETER RunTests
    Also run the NeoForge GameTest suite and refuse to install if it fails. Slower (a few
    minutes) but this is what catches a broken build before it reaches your world.

.PARAMETER ExtraMods
    Additional jars to copy into the mods folder, for example a freshly downloaded vehicle_mod.

.EXAMPLE
    .\tools\update-and-install.ps1
    Pull, build, install.

.EXAMPLE
    .\tools\update-and-install.ps1 -RunTests
    Pull, build, run the full GameTest suite, install only if it passes.

.EXAMPLE
    .\tools\update-and-install.ps1 -ExtraMods "$env:USERPROFILE\Downloads\vehicle_mod-1.0.0.jar"
    Also install a downloaded dependency alongside the mod.
#>
[CmdletBinding()]
param(
    [string] $Repo = (Split-Path -Parent $PSScriptRoot),
    [string] $ModsDir = (Join-Path $env:APPDATA '.minecraft\mods'),
    [string] $JdkHome,
    [switch] $SkipPull,
    [switch] $RunTests,
    [string[]] $ExtraMods = @()
)

# Deliberately not 'Stop': git and Gradle both write ordinary progress and warnings to stderr,
# which PowerShell would otherwise promote to terminating errors and abort a perfectly good
# build. Every external command below is checked through $LASTEXITCODE instead.
$ErrorActionPreference = 'Continue'
$script:StepNumber = 0

# Runs an external command, returning its combined output as plain strings. PowerShell wraps
# native stderr in ErrorRecord objects, so they are flattened here to keep callers simple.
function Invoke-Native {
    param(
        [Parameter(Mandatory)] [string] $Exe,
        [Parameter(ValueFromRemainingArguments)] [string[]] $Arguments = @()
    )
    $output = & $Exe @Arguments 2>&1 | ForEach-Object { "$_" }
    $script:NativeExit = $LASTEXITCODE
    return $output
}

function Step([string] $Message) {
    $script:StepNumber++
    Write-Host ''
    Write-Host "[$script:StepNumber] $Message" -ForegroundColor Cyan
}

function Fail([string] $Message) {
    Write-Host ''
    Write-Host "FAILED: $Message" -ForegroundColor Red
    exit 1
}

function Note([string] $Message) {
    Write-Host "    $Message" -ForegroundColor DarkGray
}

# --- Preflight ---------------------------------------------------------------------------

Step 'Checking the checkout'
if (-not (Test-Path (Join-Path $Repo 'gradlew.bat'))) {
    Fail "no Gradle wrapper in '$Repo'. Pass -Repo <path to the checkout>."
}
Set-Location $Repo
Note "repo:      $Repo"
Note "mods dir:  $ModsDir"

# The mod targets Java 25, but NeoForm's own tooling requests a 21 toolchain. Rather than
# demand both JDKs, point NeoFormRuntime at whichever JDK we are building with.
if (-not $JdkHome) {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $candidates += Get-ChildItem 'C:\Program Files\Microsoft', 'C:\Program Files\Java',
        'C:\Program Files\Eclipse Adoptium' -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match 'jdk-?(2[1-9]|[3-9][0-9])' } |
        Sort-Object Name -Descending |
        ForEach-Object { $_.FullName }
    $JdkHome = $candidates | Where-Object { Test-Path (Join-Path $_ 'bin\java.exe') } | Select-Object -First 1
}
if (-not $JdkHome -or -not (Test-Path (Join-Path $JdkHome 'bin\java.exe'))) {
    Fail 'no JDK found. Install JDK 25 or pass -JdkHome <path>.'
}
$env:JAVA_HOME = $JdkHome
$env:PATH = "$JdkHome\bin;$env:PATH"
Note "jdk:       $JdkHome"

# --- Pull --------------------------------------------------------------------------------

if (-not $SkipPull) {
    Step 'Pulling the latest sources'
    $dirty = @(Invoke-Native git status --porcelain | Where-Object { $_ -notmatch '^\?\?' })
    if ($dirty.Count -gt 0) {
        Write-Host '    Local changes present; they will be set aside and restored:' -ForegroundColor Yellow
        $dirty | ForEach-Object { Note $_ }
    }
    $branch = (Invoke-Native git rev-parse --abbrev-ref HEAD | Select-Object -First 1).Trim()
    Note "branch:    $branch"

    Invoke-Native git fetch origin --quiet | Out-Null
    if ($script:NativeExit -ne 0) { Fail 'git fetch failed (offline, or no access to the remote).' }

    $behind = (Invoke-Native git rev-list --count "HEAD..origin/$branch" | Select-Object -First 1)
    if ($script:NativeExit -ne 0 -or -not $behind) { $behind = '0' }
    if ($behind -eq '0') {
        Note 'already up to date'
    } else {
        Note "$behind new commit(s) upstream"
        $stashed = $false
        if ($dirty.Count -gt 0) {
            Invoke-Native git stash push --quiet --message 'update-and-install autostash' | Out-Null
            $stashed = $script:NativeExit -eq 0
        }
        Invoke-Native git rebase "origin/$branch" | ForEach-Object { Note $_ }
        $rebaseFailed = $script:NativeExit -ne 0
        if ($stashed) {
            Invoke-Native git stash pop --quiet | Out-Null
            if ($script:NativeExit -ne 0) {
                Fail 'your local changes conflicted with upstream; they are safe in `git stash list`.'
            }
        }
        if ($rebaseFailed) {
            Fail 'rebase hit a conflict. Resolve it, then re-run with -SkipPull.'
        }
    }
    Note "at:        $(Invoke-Native git log --oneline -1 | Select-Object -First 1)"
}

# --- Build -------------------------------------------------------------------------------

Step 'Building'
$initScript = Join-Path $env:TEMP 'cyberdeck-nfrt-toolchain.init.gradle'
@"
// Generated by tools/update-and-install.ps1. NeoFormRuntime requests a Java 21 toolchain even
// though this mod targets 25; point its tasks at the JDK we are actually building with so a
// single JDK install is enough.
allprojects { p ->
    p.tasks.configureEach { t ->
        if (t.getClass().getName().contains('nfrtgradle') && t.hasProperty('javaExecutable')) {
            t.javaExecutable.set('$(($JdkHome -replace '\\','/'))/bin/java.exe')
        }
    }
}
"@ | ForEach-Object {
    # Windows PowerShell's -Encoding utf8 emits a BOM, and Gradle's Groovy parser rejects one at
    # the top of an init script ("Unexpected character"). Write UTF-8 without it.
    [System.IO.File]::WriteAllText($initScript, $_, (New-Object System.Text.UTF8Encoding $false))
}

Invoke-Native (Join-Path $Repo 'gradlew.bat') 'build' '--no-configuration-cache' `
    '--init-script' $initScript '--console=plain' |
    Where-Object { $_ -match 'error:|warning:|BUILD |Task :|FAILURE|What went wrong|^\s+>' } |
    ForEach-Object { Note $_ }
if ($script:NativeExit -ne 0) { Fail 'the build failed; nothing was installed.' }

$jar = Get-ChildItem (Join-Path $Repo 'build\libs') -Filter 'cyberdeck-*.jar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch 'sources|javadoc' } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $jar) { Fail 'the build reported success but produced no jar.' }
Note "built:     $($jar.Name)  ($([math]::Round($jar.Length / 1MB, 1)) MB)"

# --- Optional verification ---------------------------------------------------------------

if ($RunTests) {
    Step 'Running the GameTest suite'
    Note 'this takes a few minutes; nothing is installed if it fails'
    Invoke-Native (Join-Path $Repo 'gradlew.bat') 'runGameTestServer' '--no-configuration-cache' `
        '--init-script' $initScript '--console=plain' |
        Where-Object { $_ -match 'required tests|failed at|BUILD ' } |
        ForEach-Object { Note $_ }
    if ($script:NativeExit -ne 0) { Fail 'GameTests failed; nothing was installed.' }
}

# --- Install -----------------------------------------------------------------------------

Step 'Installing'
if (-not (Test-Path $ModsDir)) {
    Fail "mods directory '$ModsDir' does not exist. Launch Minecraft once, or pass -ModsDir."
}

# Minecraft holds the jar open while running, which would otherwise fail mid-copy and leave a
# truncated mod behind.
function Install-Jar([string] $Source, [string] $Label) {
    $destination = Join-Path $ModsDir (Split-Path -Leaf $Source)
    $before = if (Test-Path $destination) {
        (Get-FileHash $destination -Algorithm SHA256).Hash.ToLower()
    } else { $null }
    try {
        Copy-Item $Source $destination -Force -ErrorAction Stop
    } catch {
        Fail "could not write '$destination'. Is Minecraft still running? ($_)"
    }
    $sourceHash = (Get-FileHash $Source -Algorithm SHA256).Hash.ToLower()
    $installed = (Get-FileHash $destination -Algorithm SHA256).Hash.ToLower()
    if ($sourceHash -ne $installed) {
        Fail "$Label copied but the installed hash does not match the build."
    }
    $state = if ($before -eq $installed) { 'unchanged' } else { 'updated' }
    Note ("{0,-30} {1}  {2}" -f (Split-Path -Leaf $Source), $installed.Substring(0, 12), $state)
}

Install-Jar $jar.FullName 'the mod'
foreach ($extra in $ExtraMods) {
    if (-not (Test-Path $extra)) { Fail "extra mod '$extra' not found." }
    Install-Jar $extra 'the extra mod'
}

Write-Host ''
Write-Host 'Done. Launch Minecraft.' -ForegroundColor Green
