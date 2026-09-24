<#
.SYNOPSIS
  The test rig: boot the project's own emulator, deploy the app, and drive it — in one command.

.DESCRIPTION
  Everything this project measured before it existed was measured on the owner's phone, which meant
  every run waited on a handset being awake, unlocked and parked on the right page. Twice a session
  stalled outright on that: a locked screen still lists the overlay window and still swallows every
  injected tap, so a run looks broken when it is only asleep. This script removes the handset from
  the loop for everything that does not specifically need one.

  It also replaces the six adb calls that were retyped by hand a dozen times per sitting — install,
  rebind, launch, press, screenshot, read the log — each of which is one transcription error away
  from a wrong conclusion.

  THE EMULATOR IT TALKS TO IS PINNED, AND THAT IS THE POINT.
  This machine also runs emulators belonging to other projects' integration suites, worked in other
  sessions, which this project must never touch. So there is no device auto-discovery here at all:
  the serial is `emulator-<console port>` for this project's own AVD and nothing else. A bare adb
  with several devices attached either fails or picks one for you, and "picks one for you" is how a
  test run lands in somebody else's suite. Pass -Serial only when you mean it.

  DRIVE BY THE RECTANGLES THE APP LOGS, NEVER BY NUMBERS READ OFF A SCREENSHOT.
  The pad logs where each of its buttons actually landed, and the debug target logs where each of
  its text blocks landed, both in raw screen pixels at layout time. `press` and `blocks` read those
  lines back. Computing a coordinate from a screenshot instead is how a tap once missed the pad by
  11 px, landed on the page, and was written down as "gestures pass through the overlay" — a wrong
  conclusion that cost a whole round of measurement.

.EXAMPLE
  .\scripts\rig.ps1 go
  # Boot, build, install, rebind the service, launch the debug target, show the pad, report every
  # rectangle it and the target logged, and take a screenshot. The whole loop, cold, in one command.

.EXAMPLE
  .\scripts\rig.ps1 press 'char/right'
  .\scripts\rig.ps1 log 40
  # One press, then what the pad said about it.

.EXAMPLE
  .\scripts\rig.ps1 avd
  # What the AVD should look like, and whether this machine has it.
#>

[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('go', 'up', 'down', 'build', 'install', 'rebind', 'target', 'pad', 'blocks', 'buttons', 'press', 'tap', 'shot', 'log', 'status', 'avd')]
    [string] $Action = 'status',

    # Positional arguments for the action: `press <label>`, `tap <x> <y>`, `log [lines]`, `shot [path]`.
    [Parameter(Position = 1, ValueFromRemainingArguments = $true)]
    [string[]] $Rest = @(),

    # Only ever pass this deliberately. See the warning in the description.
    [string] $Serial,

    # `go` without the Gradle build, for when the APK on disk is already the one you want.
    [switch] $NoBuild
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
trap {
    # $Error[0] reports the real fault line; a different exception there means it is stale.
    if ($Error.Count -gt 0 -and [object]::ReferenceEquals($Error[0].Exception, $_.Exception)) {
        throw $Error[0]
    }
    throw $_
}

# --------------------------------------------------------------------------------------------------
# What this rig is, in constants.
# --------------------------------------------------------------------------------------------------

# The console port is deliberately far from the 5554 the first emulator on a machine takes, so this
# project's emulator can never be confused with another project's by a serial typed from memory.
$RigConsolePort = 5570
$RigAvdName = 'asc_test'
$RigSerial = if ($Serial) { $Serial } else { "emulator-$RigConsolePort" }

$AppId = 'dev.mnaoumov.asc'
$ServiceComponent = "$AppId/$AppId.AscAccessibilityService"
$TargetActivity = "$AppId/.TargetActivity"
$LogTag = 'ASC'

$RepoRoot = $PSScriptRoot | Split-Path -Parent
$ApkPath = $RepoRoot | Join-Path -ChildPath 'app\build\outputs\apk\debug\app-debug.apk'
$ShotDir = $RepoRoot | Join-Path -ChildPath 'build\rig'

# Android Studio's JBR. There is no JDK on PATH on this machine, so the build fails without this.
$JbrPath = 'C:\Program Files\Android\Android Studio\jbr'

$SdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:LOCALAPPDATA | Join-Path -ChildPath 'Android\Sdk' }
$EmulatorExe = $SdkRoot | Join-Path -ChildPath 'emulator\emulator.exe'
$AvdHome = $env:USERPROFILE | Join-Path -ChildPath '.android\avd'

# How long to wait for a cold boot. Measured at ~30 s headless on this machine for this AVD; the
# ceiling is generous because a machine running several other emulators is where 30 s becomes 90.
$BootTimeoutSeconds = 240

# --------------------------------------------------------------------------------------------------
# adb
#
# Deliberately a bare call rather than this machine's exec wrapper: that wrapper is a module from the
# owner's private setup, and a script checked into this repo has to run for anyone who clones it with
# an SDK and nothing else. The exit code is therefore checked here by hand, which is the part the
# wrapper would otherwise be doing.
# --------------------------------------------------------------------------------------------------

<#
  ALWAYS call this as `Invoke-Adb -Arguments @('shell', 'screencap', '-p', $path)`, never as
  `Invoke-Adb shell screencap -p $path`.

  The loose spelling reads better and is a trap: PowerShell binds a leading-dash token to this
  function's own parameters first, and [CmdletBinding()] quietly adds the common ones. So `-p` is
  rejected as ambiguous between -ProgressAction and -PipelineVariable, which at least fails loudly —
  while `-d` binds to -Debug, is removed from what adb is handed, and turns `logcat -d` from a dump
  into a follow that never returns. Both were live here on 2026-09-20; the second is far the worse,
  because a hang has no error message to read.
#>
function Invoke-Adb {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string[]] $Arguments,

        # For the calls where a non-zero exit is an answer rather than a failure.
        [switch] $AllowFailure
    )

    $output = & adb -s $RigSerial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0 -and -not $AllowFailure) {
        throw "adb -s $RigSerial $($Arguments -join ' ') exited $LASTEXITCODE`n$($output -join "`n")"
    }
    return $output
}

<#
  An adb call that cannot hang for ever, for the paths where hanging is the failure being waited on.

  A deadline loop built out of ordinary calls does not have a deadline: a wedged guest leaves even
  `adb shell echo` unanswered — measured at over 60 s here on 2026-09-20 — and the loop never
  reaches its own clock to notice it is out of time. So the waiting paths use this, which kills the
  child and answers $null instead. Everything else uses the plain call deliberately: a hang there is
  a caller pressing Ctrl+C with the real duration in front of them, which is better than a timeout
  guessed in advance.
#>
function Invoke-AdbBounded {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string[]] $Arguments,

        [int] $TimeoutSeconds = 20
    )

    $outFile = [System.IO.Path]::GetTempFileName()
    $errFile = [System.IO.Path]::GetTempFileName()
    try {
        $proc = Start-Process -FilePath 'adb' -ArgumentList $Arguments -NoNewWindow -PassThru `
            -RedirectStandardOutput $outFile -RedirectStandardError $errFile
        if (-not $proc.WaitForExit($TimeoutSeconds * 1000)) {
            $proc.Kill()
            return $null
        }
        return @(Get-Content -Path $outFile -ErrorAction SilentlyContinue)
    } finally {
        Remove-Item -Path $outFile, $errFile -Force -ErrorAction SilentlyContinue
    }
}

function Get-AttachedSerials {
    [OutputType([string[]])]
    param()

    $lines = & adb devices
    if ($LASTEXITCODE -ne 0) { throw "adb devices exited $LASTEXITCODE" }
    return , [string[]]@(
        $lines |
            Select-String -Pattern '^(\S+)\s+device$' |
            ForEach-Object -Process { $_.Matches[0].Groups[1].Value }
    )
}

<#
  `device`, `offline`, or `absent` — and the middle one is why this is not a boolean.

  A wedged guest sits in adb's list as `offline` for as long as its process lives. Read that as "not
  attached" and the obvious next move is to boot it, which cannot work: the emulator refuses to run
  the same AVD twice with `FATAL | Running multiple emulators with the same AVD is an experimental
  feature`, so the rig reports a launch failure whose text is about a feature nobody asked for while
  the real answer is that the machine is too busy to finish a boot. Measured here 2026-09-20 with
  seven other jobs pinning every core.
#>
function Get-RigState {
    [OutputType([string])]
    param()

    $lines = & adb devices
    if ($LASTEXITCODE -ne 0) { throw "adb devices exited $LASTEXITCODE" }
    $match = $lines | Select-String -Pattern "^$([regex]::Escape($RigSerial))\s+(\S+)$"
    if (-not $match) { return 'absent' }
    return $match.Matches[0].Groups[1].Value
}

function Test-RigAttached {
    return (Get-RigState) -eq 'device'
}

<#
  The emulator processes running THIS AVD, newest last.

  Found by command line rather than by port, because a wedged emulator has often stopped listening
  on its console port while its process is very much alive — which is exactly the state that needs
  detecting.
#>
function Get-AvdProcesses {
    return @(
        Get-CimInstance -ClassName Win32_Process -Filter "Name = 'emulator.exe' OR Name LIKE 'qemu-system%'" |
            Where-Object -FilterScript { $_.CommandLine -and $_.CommandLine -match "-avd\s+$([regex]::Escape($RigAvdName))\b" }
    )
}

function Assert-RigAttached {
    if (Test-RigAttached) { return }

    $state = Get-RigState
    if ($state -eq 'absent') {
        throw "$RigSerial is not attached. Run `".\scripts\rig.ps1 up`" first. (Attached now: $((Get-AttachedSerials) -join ', '))"
    }
    throw "$RigSerial is '$state', not ready. It is usually wedged for want of CPU; `".\scripts\rig.ps1 down`" then `"up`" starts over."
}

# --------------------------------------------------------------------------------------------------
# The AVD
# --------------------------------------------------------------------------------------------------

<#
  What the AVD should look like, and whether this machine has one.

  The image itself is ~8.7 GB and cannot live in the repo, so what the repo can own is the shape it
  must have and a check that says plainly when it does not. Both numbers below were paid for:

  - 720x1520 at 320 dpi with 2560 MB. At the phone's own 1344x2992 with Chrome under a software
    renderer the guest wedged three times in an hour, once taking the whole VM down with it.
  - Console port 5570. Nothing technical forces it; it is far enough from the default 5554 that a
    serial typed from memory cannot reach another project's emulator.
#>
function Show-AvdState {
    $ini = $AvdHome | Join-Path -ChildPath "$RigAvdName.ini"
    $dir = $AvdHome | Join-Path -ChildPath "$RigAvdName.avd"

    Write-Host -Object "AVD           : $RigAvdName"
    Write-Host -Object "Console port  : $RigConsolePort  (serial $RigSerial)"
    Write-Host -Object "Expected shape: 720x1520, 320 dpi, 2560 MB RAM, x86_64"
    Write-Host -Object "Location      : $dir"

    if (-not (Test-Path -Path $ini) -or -not (Test-Path -Path $dir)) {
        Write-Host -Object ''
        Write-Host -Object 'NOT PRESENT on this machine.'
        Write-Host -Object ''
        Write-Host -Object @'
There is no cmdline-tools install here, so there is no `avdmanager` to create one with, and a
hand-written config.ini boots to a hung QEMU rather than to an error. The route that works:

  1. In Android Studio's Device Manager, create any x86_64 AVD on a recent system image.
  2. Close Studio. Copy its directory to `asc_test.avd` and its `.ini` to `asc_test.ini`, both
     under ~/.android/avd, SKIPPING `snapshots/` and the `*.img.qcow2` backing files (about half
     the bytes, and all of them regenerate).
  3. In `asc_test.ini` set `path` and `path.rel` to the new directory.
  4. In `asc_test.avd/config.ini` set:
       AvdId=asc_test
       avd.ini.displayname=asc_test
       hw.lcd.width=720
       hw.lcd.height=1520
       hw.lcd.density=320
       hw.ramSize=2560
       skin.name=720x1520
       fastboot.forceColdBoot=yes
  5. `.\scripts\rig.ps1 up` — first boot takes about 40 s.
'@
        return
    }

    Write-Host -Object 'Present.'

    $config = $dir | Join-Path -ChildPath 'config.ini'
    $expected = [ordered]@{
        'hw.lcd.width'   = '720'
        'hw.lcd.height'  = '1520'
        'hw.lcd.density' = '320'
        'hw.ramSize'     = '2560'
    }
    $actual = @{}
    Get-Content -Path $config | ForEach-Object -Process {
        if ($_ -match '^([^=]+)=(.*)$') { $actual[$Matches[1]] = $Matches[2] }
    }

    $drift = @(
        $expected.Keys | Where-Object -FilterScript {
            -not $actual.ContainsKey($_) -or $actual[$_] -ne $expected[$_]
        }
    )
    if ($drift.Count -eq 0) {
        Write-Host -Object 'Shape matches.'
    } else {
        # A warning rather than a throw: a different shape may well boot, it just is not the one the
        # measurements in AGENTS.md were taken on, and a reader deserves to know which they have.
        $drift | ForEach-Object -Process {
            $have = if ($actual.ContainsKey($_)) { $actual[$_] } else { '(absent)' }
            Write-Warning -Message "$_ is $have, expected $($expected[$_])"
        }
    }

    Write-Host -Object "Attached now  : $(if (Test-RigAttached) { 'yes' } else { 'no' })"
}

<#
  Wait for the guest to become usable, or say plainly that it did not.

  `sys.boot_completed` is the only honest ready signal: adb reports the device long before the
  framework will accept an `am start`.
#>
function Wait-RigReady([int] $TimeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 3
        # Bounded, because this is the loop whose whole job is to outlive a guest that never answers.
        $booted = "$(Invoke-AdbBounded -Arguments @('-s', $RigSerial, 'shell', 'getprop', 'sys.boot_completed'))".Trim()
        if ($booted -eq '1') {
            Invoke-AdbBounded -Arguments @('-s', $RigSerial, 'shell', 'input', 'keyevent', '82') | Out-Null   # dismiss the lock screen
            return $true
        }
    }
    return $false
}

function Start-Rig {
    if (Test-RigAttached) {
        Write-Host -Object "$RigSerial is already up."
        return
    }

    # Never launch a second instance of an AVD that already has one. See Get-RigState for what the
    # emulator does instead if you do, and why its complaint names the wrong problem.
    $running = @(Get-AvdProcesses)
    if ($running.Count -gt 0) {
        $state = Get-RigState
        Write-Host -Object "$RigAvdName is already running (pid $(($running | ForEach-Object -Process { $_.ProcessId }) -join ', ')) but adb reports it '$state'. Waiting for it."
        if (Wait-RigReady -TimeoutSeconds $BootTimeoutSeconds) {
            Write-Host -Object "$RigSerial is up."
            return
        }
        throw "$RigAvdName has been running for a while and is still '$state'. It is wedged, usually because the machine has no CPU left for it — check what else is running, then `".\scripts\rig.ps1 down`" and start over."
    }

    if (-not (Test-Path -Path ($AvdHome | Join-Path -ChildPath "$RigAvdName.ini"))) {
        Show-AvdState
        throw "The $RigAvdName AVD does not exist on this machine; see the recipe above."
    }
    if (-not (Test-Path -Path $EmulatorExe)) {
        throw "No emulator at $EmulatorExe. Set ANDROID_HOME, or install the SDK's emulator package."
    }

    <#
      -port pins the console port, which is what makes the serial predictable and therefore what
      makes the pinning at the top of this script mean anything.

      -no-snapshot-save is not a preference: a saved snapshot is how a rig quietly stops being a
      clean fixture, and this AVD is already configured to cold-boot. The pair keeps every run
      starting from the same state, which is the only reason an expected offset is a number rather
      than a hope.

      -no-window is not a preference either: it is what keeps the guest alive. Windowed, the
      emulator's Qt UI needs opengl32sw, which it cannot load here; it falls back to system OpenGL,
      and the guest then wedges under the first real work — the first `adb install` never returns
      while `adb devices` still lists a healthy device and qemu takes no CPU at all. Its log ends on
      "Showing crashdialog to get consent.", on a window nobody is looking at, which is why the
      wedge reads as a hang rather than a crash. `-gpu swiftshader_indirect` alone does not help:
      it is the window, not the guest renderer, that needs the missing module. Headless it boots in
      ~30 s and survives installs, rebinds and dozens of presses, and `screencap` works without a
      window, so `shot` loses nothing.

      -no-metrics keeps a first boot on a fresh machine from stopping on the usage-stats question.
    #>
    Write-Host -Object "Booting $RigAvdName headless on port $RigConsolePort ..."
    Start-Process -FilePath $EmulatorExe -ArgumentList @(
        '-avd', $RigAvdName,
        '-port', "$RigConsolePort",
        '-no-snapshot-save',
        '-no-boot-anim',
        '-no-window',
        '-gpu', 'swiftshader_indirect',
        '-no-metrics'
    ) -WindowStyle Hidden

    if (Wait-RigReady -TimeoutSeconds $BootTimeoutSeconds) {
        Write-Host -Object "$RigSerial is up."
        return
    }
    throw "$RigAvdName did not finish booting within $BootTimeoutSeconds s."
}

<#
  Take this project's emulator down, and leave nothing behind that stops the next boot.

  Three steps, in order of politeness. `emu kill` goes down the console connection to THIS serial,
  so it cannot reach another project's emulator even if one is running — but a wedged guest has
  usually stopped answering its console, which is why the process kill exists, and it is matched by
  command line so it too can only ever name this AVD.

  The lock sweep is the part that is easy to leave out and then spend an afternoon on: an emulator
  that dies without cleaning up leaves `multiinstance.lock` and `hardware-qemu.ini.lock` behind, and
  the next boot then FATALs about running multiple emulators of the same AVD — a message about a
  feature nobody asked for, describing a process that no longer exists.
#>
function Stop-Rig {
    if (Test-RigAttached) {
        # Bounded: `down` is the way OUT of a wedged emulator, and a wedged one does not answer its
        # console either — an unbounded kill here hangs the one command that was going to fix it.
        Invoke-AdbBounded -Arguments @('-s', $RigSerial, 'emu', 'kill') -TimeoutSeconds 15 | Out-Null
        Start-Sleep -Seconds 3
    }

    @(Get-AvdProcesses) | ForEach-Object -Process {
        Write-Host -Object "Killing pid $($_.ProcessId) ($($_.Name))"
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    }
    Start-Sleep -Seconds 2

    if (@(Get-AvdProcesses).Count -eq 0) {
        $dir = $AvdHome | Join-Path -ChildPath "$RigAvdName.avd"
        Get-ChildItem -Path $dir -Filter '*.lock' -Force -ErrorAction SilentlyContinue |
            ForEach-Object -Process {
                Remove-Item -Path $_.FullName -Recurse -Force -ErrorAction SilentlyContinue
                if (-not (Test-Path -Path $_.FullName)) { Write-Host -Object "Cleared stale $($_.Name)" }
            }
    }

    Write-Host -Object "$RigSerial is down."
}

# --------------------------------------------------------------------------------------------------
# Build and deploy
# --------------------------------------------------------------------------------------------------

function Build-App {
    $env:JAVA_HOME = $JbrPath
    Push-Location -Path $RepoRoot
    try {
        & ($RepoRoot | Join-Path -ChildPath 'gradlew.bat') ':app:assembleDebug'
        if ($LASTEXITCODE -ne 0) { throw "gradlew :app:assembleDebug exited $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
    Write-Host -Object "Built $ApkPath"
}

function Install-App {
    Assert-RigAttached
    if (-not (Test-Path -Path $ApkPath)) {
        throw "No APK at $ApkPath. Run `".\scripts\rig.ps1 build`" first."
    }
    Invoke-Adb -Arguments @('install', '-r', $ApkPath) | Out-Null
    Write-Host -Object "Installed $AppId on $RigSerial."
}

function Get-EnabledServices {
    [OutputType([string[]])]
    param()

    # The comma and the explicit [string[]] matter: PowerShell unwraps a single-element array on
    # return, and a caller that then appends to what it thinks is a list instead concatenates two
    # component names into one nonsense value. That is not hypothetical — it happened on the phone,
    # and the device answered by emptying the list, taking the owner's password manager with it.
    $value = Invoke-Adb -AllowFailure -Arguments @('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services')
    if ($null -eq $value) { return , [string[]]@() }
    $value = "$value".Trim()
    if ($value -eq '' -or $value -eq 'null') { return , [string[]]@() }
    return , [string[]]@($value -split ':' | Where-Object -FilterScript { $_ -ne '' })
}

function Set-EnabledServices([string[]] $Services) {
    $value = (@($Services | Where-Object -FilterScript { $_ -ne '' }) -join ':')
    if ($value -eq '') {
        Invoke-Adb -Arguments @('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', '""') | Out-Null
    } else {
        Invoke-Adb -Arguments @('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', $value) | Out-Null
        Invoke-Adb -Arguments @('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1') | Out-Null
    }
    Start-Sleep -Milliseconds 400
}

<#
  Force the service to actually connect.

  A reinstall can leave the service listed in settings with no accessibility connection at all: it
  answers commands while `windows` reports zero and the node tree comes back empty, which reads
  exactly like a broken build. Rewriting the string — take ours out, put it back — is what makes the
  system rebind. Everything else in the list is preserved on both halves, because this setting is
  shared with whatever else the machine relies on.
#>
function Restore-ServiceBinding {
    Assert-RigAttached

    $others = @(Get-EnabledServices | Where-Object -FilterScript { $_ -ne $ServiceComponent })
    Set-EnabledServices $others
    Set-EnabledServices (@($others) + @($ServiceComponent))

    $now = Get-EnabledServices
    if ($now -notcontains $ServiceComponent) {
        throw "The device did not keep the service in the enabled list. Now enabled: $($now -join ', ')"
    }
    Write-Host -Object 'Service rebound; the pad appears as soon as it connects.'
    if ($others.Count -gt 0) {
        Write-Host -Object "Left alone: $($others -join ', ')"
    }
}

# --------------------------------------------------------------------------------------------------
# Drive
# --------------------------------------------------------------------------------------------------

function Clear-Log {
    Invoke-Adb -AllowFailure -Arguments @('logcat', '-c') | Out-Null
}

function Get-Log {
    [CmdletBinding()]
    param([int] $Lines = 200)

    return @(Invoke-Adb -AllowFailure -Arguments @('logcat', '-s', "${LogTag}:I", '-d', '-t', "$Lines"))
}

<#
  The rectangles the app logged, as objects.

  Both producers use the same shape — `<kind> '<name>' at <x>,<y> <w>x<h>` — because both exist for
  the same reason: so a test presses a known place rather than a place somebody measured off a
  picture. The last line for a given name wins, since a relayout logs again and the newest is the
  one on screen.
#>
function Get-LoggedRects {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string] $Kind,

        # The lines to read. Defaults to the device's log; passing them makes the parse and the
        # centre arithmetic testable with no device at all, which matters because this is the one
        # piece of the rig whose output something else then aims a finger at.
        [string[]] $Lines
    )

    if ($null -eq $Lines) { $Lines = Get-Log -Lines 400 }

    $found = [ordered]@{}
    $Lines | ForEach-Object -Process {
        if ($_ -match "$Kind '([^']+)' at (\d+),(\d+) (\d+)x(\d+)") {
            $found[$Matches[1]] = [pscustomobject]@{
                Name    = $Matches[1]
                X       = [int] $Matches[2]
                Y       = [int] $Matches[3]
                Width   = [int] $Matches[4]
                Height  = [int] $Matches[5]
                CentreX = [int] $Matches[2] + [int] ([int] $Matches[4] / 2)
                CentreY = [int] $Matches[3] + [int] ([int] $Matches[5] / 2)
            }
        }
    }
    return @($found.Values)
}

function Get-PadButtons { return Get-LoggedRects -Kind 'pad button' }
function Get-TargetBlocks { return Get-LoggedRects -Kind 'target' }

function Start-Target {
    [CmdletBinding()]
    param(
        # For the callers that have already cleared the log and are collecting several producers'
        # lines into one harvest.
        [switch] $KeepLog
    )

    Assert-RigAttached
    if (-not $KeepLog) { Clear-Log }
    Invoke-Adb -Arguments @('shell', 'am', 'start', '-n', $TargetActivity) | Out-Null
    Start-Sleep -Seconds 2
    $blocks = @(Get-TargetBlocks)
    if ($blocks.Count -eq 0) {
        Write-Warning -Message 'The target activity logged no blocks. It is debug-only — check the debug APK is the one installed.'
    }
    return $blocks
}

<#
  Put the pad on screen and read back where its buttons landed.

  The pad appears by itself the moment the service connects, and it logs its rectangles then — so a
  rebind is both the way to make it appear and the way to make it say where it is. That is the lever
  used here when the log holds nothing, rather than the launcher's own "Show the pad" button, whose
  position is not logged anywhere and so could only be pressed by guessing at a coordinate — the one
  thing this script exists to avoid.
#>
function Show-Pad {
    Assert-RigAttached

    $buttons = @(Get-PadButtons)
    if ($buttons.Count -gt 0) { return $buttons }

    Restore-ServiceBinding
    Start-Sleep -Seconds 2
    $buttons = @(Get-PadButtons)
    if ($buttons.Count -eq 0) {
        Write-Warning -Message 'The pad logged no buttons. Either the service is not connected, or the pad was closed with its own ✕ — the launcher screen''s "Show the pad" button puts it back.'
    }
    return $buttons
}

function Invoke-Tap([int] $X, [int] $Y) {
    Assert-RigAttached
    Invoke-Adb -Arguments @('shell', 'input', 'tap', "$X", "$Y") | Out-Null
}

<#
  Press a pad button by its label.

  Injected input is the right way to test these buttons and the service's own dispatchGesture is
  not: they are different input paths, and the distinction has already mattered here once.
#>
function Invoke-Press([string] $Label) {
    $buttons = @(Get-PadButtons)
    if ($buttons.Count -eq 0) {
        throw 'No pad button rectangles in the log. Run "rig.ps1 pad" first, or "rig.ps1 go".'
    }

    $match = @($buttons | Where-Object -FilterScript { $_.Name -eq $Label })
    if ($match.Count -eq 0) {
        $match = @($buttons | Where-Object -FilterScript { $_.Name -like "*$Label*" })
    }
    if ($match.Count -eq 0) {
        throw "No pad button matching '$Label'. Known: $(($buttons | ForEach-Object -Process { $_.Name }) -join ', ')"
    }
    if ($match.Count -gt 1) {
        throw "'$Label' matches $($match.Count) buttons: $(($match | ForEach-Object -Process { $_.Name }) -join ', ')"
    }

    $button = $match[0]
    Write-Host -Object "Pressing '$($button.Name)' at $($button.CentreX),$($button.CentreY)"
    Invoke-Tap -X $button.CentreX -Y $button.CentreY
}

<#
  Screenshot.

  Pulled through a file on the device rather than piped from `exec-out`, because a PowerShell
  redirect mangles binary on the way through and produces a PNG that will not open — a failure that
  reads as a broken screencap.
#>
function Save-Shot([string] $Path) {
    Assert-RigAttached
    if (-not $Path) {
        if (-not (Test-Path -Path $ShotDir)) { New-Item -ItemType Directory -Path $ShotDir | Out-Null }
        $Path = $ShotDir | Join-Path -ChildPath ("shot-{0:yyyyMMdd-HHmmss}.png" -f (Get-Date))
    }
    $onDevice = '/sdcard/rig-shot.png'
    Invoke-Adb -Arguments @('shell', 'screencap', '-p', $onDevice) | Out-Null
    Invoke-Adb -Arguments @('pull', $onDevice, $Path) | Out-Null
    Invoke-Adb -AllowFailure -Arguments @('shell', 'rm', '-f', $onDevice) | Out-Null
    Write-Host -Object "Screenshot: $Path"
    return $Path
}

function Show-Status {
    Write-Host -Object "Serial        : $RigSerial (adb says '$(Get-RigState)')"
    Write-Host -Object "AVD processes : $((@(Get-AvdProcesses) | ForEach-Object -Process { "$($_.Name) $($_.ProcessId)" }) -join ', ')"
    Write-Host -Object "Also attached : $(((Get-AttachedSerials) | Where-Object -FilterScript { $_ -ne $RigSerial }) -join ', ')"
    if (-not (Test-RigAttached)) { return }

    $enabled = Get-EnabledServices
    Write-Host -Object "Service       : $(if ($enabled -contains $ServiceComponent) { 'enabled' } else { 'OFF' })"
    Write-Host -Object "Installed     : $(if ("$(Invoke-Adb -AllowFailure -Arguments @('shell', 'pm', 'list', 'packages', $AppId))".Trim()) { 'yes' } else { 'no' })"

    $top = "$(Invoke-Adb -AllowFailure -Arguments @('shell', 'dumpsys', 'activity', 'activities'))" |
        Select-String -Pattern 'topResumedActivity.*\{([^}]*)\}'
    if ($top) { Write-Host -Object "Foreground    : $($top.Matches[0].Groups[1].Value)" }
}

function Show-Rects([string] $Heading, $Rects) {
    Write-Host -Object ''
    Write-Host -Object $Heading

    # The null filter is load-bearing: a function returning nothing hands back $null, and `@($null)`
    # is a ONE-element array in PowerShell, so a plain count here reports "1 rectangle" and then
    # prints a blank row for it.
    $items = @($Rects | Where-Object -FilterScript { $null -ne $_ })
    if ($items.Count -eq 0) {
        Write-Host -Object '  (none logged)'
        return
    }
    $items | ForEach-Object -Process {
        Write-Host -Object ("  {0,-16} centre {1},{2}   ({3},{4} {5}x{6})" -f
            $_.Name, $_.CentreX, $_.CentreY, $_.X, $_.Y, $_.Width, $_.Height)
    }
}

# --------------------------------------------------------------------------------------------------
# Dot-source this file and nothing runs, so the parsing above can be exercised with no device
# attached — which is the only way to test it on a machine whose emulator is busy or absent.
# --------------------------------------------------------------------------------------------------

if ($MyInvocation.InvocationName -eq '.') { return }

switch ($Action) {
    'avd' { Show-AvdState }
    'up' { Start-Rig }
    'down' { Stop-Rig }
    'build' { Build-App }
    'install' { Install-App }
    'rebind' { Restore-ServiceBinding }
    'target' { Show-Rects -Heading 'Target blocks:' -Rects (Start-Target) }
    'pad' { Show-Rects -Heading 'Pad buttons:' -Rects (Show-Pad) }
    'blocks' { Show-Rects -Heading 'Target blocks:' -Rects (Get-TargetBlocks) }
    'buttons' { Show-Rects -Heading 'Pad buttons:' -Rects (Get-PadButtons) }
    'status' { Show-Status }

    'press' {
        if ($Rest.Count -lt 1) { throw 'press needs a button label, e.g. rig.ps1 press ''char/right''' }
        Invoke-Press -Label $Rest[0]
    }

    'tap' {
        if ($Rest.Count -lt 2) { throw 'tap needs x and y' }
        Invoke-Tap -X ([int] $Rest[0]) -Y ([int] $Rest[1])
    }

    'shot' {
        Save-Shot -Path $(if ($Rest.Count -ge 1) { $Rest[0] } else { '' }) | Out-Null
    }

    'log' {
        $lines = if ($Rest.Count -ge 1) { [int] $Rest[0] } else { 200 }
        Get-Log -Lines $lines | ForEach-Object -Process { Write-Host -Object $_ }
    }

    'go' {
        Start-Rig
        if (-not $NoBuild) { Build-App }
        Install-App

        # One clear, then both producers, then one harvest. The rebind is what brings the pad up and
        # therefore what makes it log its rectangles, so it has to come after the clear and before
        # the read — and the target is launched with the log kept, or its own launch would wipe the
        # pad's lines a moment after they were written.
        Clear-Log
        Restore-ServiceBinding
        $blocks = Start-Target -KeepLog

        Show-Rects -Heading 'Target blocks:' -Rects $blocks
        Show-Rects -Heading 'Pad buttons:' -Rects (Get-PadButtons)
        Save-Shot -Path '' | Out-Null
    }
}
