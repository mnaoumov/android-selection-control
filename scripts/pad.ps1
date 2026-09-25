<#
.SYNOPSIS
  Turn the Selection Pad's accessibility service on or off, by hand.

.DESCRIPTION
  There is no way to do this from Settings on this device, and that is not a bug in the app. Android's
  Enhanced Confirmation Mode blocks the accessibility toggle for anything it considers sideloaded, and
  it decides that from install provenance: an `adb install` leaves installerPackageName=null and
  initiatingPackageName=com.android.shell, which ECM treats as untrusted. The toggle is then not
  greyed out, it is absent — and on OxygenOS there is no "allow restricted settings" affordance to
  clear it, nor can `appops` do it, because adb's uid lacks MANAGE_APP_OPS_MODES. A trusted install
  source (Play) is the only real fix; until then, this script.

  It edits the enabled-services list in place rather than saving and restoring it, so whatever else
  you rely on — a password manager's autofill service, say — is preserved either way, and running it
  twice does nothing the first run did not.

.EXAMPLE
  .\scripts\pad.ps1 on
  .\scripts\pad.ps1 off
  .\scripts\pad.ps1 status
#>

[CmdletBinding()]
param(
  [Parameter(Position = 0)]
  [ValidateSet('on', 'off', 'status')]
  [string] $Action = 'status',

  # Only needed when more than one device is attached.
  [string] $Serial
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
trap {
  # $Error[0] reports the real fault line; a different exception there means it is stale.
  if ($Error.Count -gt 0 -and $Error[0] -is [System.Management.Automation.ErrorRecord] -and [object]::ReferenceEquals($Error[0].Exception, $_.Exception)) {
    throw $Error[0]
  }
  throw $_
}

$component = 'dev.mnaoumov.asc/dev.mnaoumov.asc.AscAccessibilityService'

<#
  Pick the phone, never an emulator.

  The emulators on this machine belong to the Obsidian projects' integration suites and are worked in
  other sessions; AGENTS.md says not to touch them, and a bare `adb` with several devices attached
  either fails or picks one for you. So resolve a physical device explicitly and say so when the
  answer is not unique.
#>
function Resolve-Serial {
  if ($Serial) { return $Serial }

  $devices = @(
    (& adb devices) |
      Select-String -Pattern '^(\S+)\s+device$' |
      ForEach-Object { $_.Matches[0].Groups[1].Value } |
      Where-Object { $_ -notlike 'emulator-*' }
  )

  if ($devices.Count -eq 1) { return $devices[0] }
  if ($devices.Count -eq 0) { throw 'No physical device attached (emulators are ignored on purpose). Plug the phone in, or pass -Serial.' }
  throw "More than one physical device attached: $($devices -join ', '). Pass -Serial to choose."
}

$adb = @('-s', (Resolve-Serial))

<#
  Note the explicit [string[]] and the comma.

  PowerShell unwraps a single-element array on return, so `return @($one)` hands back a STRING — and
  then `$enabled + $component` concatenates two component names into one nonsense value instead of
  appending. That is not hypothetical: it wrote
  "com.x8bit.bitwarden/...AccessibilityServicedev.mnaoumov.asc/...AscAccessibilityService", which the
  device rejected by emptying the list, disabling the owner's password manager along with this app.
#>
function Get-Enabled {
  [OutputType([string[]])]
  param()

  $value = (& adb @adb shell settings get secure enabled_accessibility_services)
  if ($null -eq $value) { return , [string[]]@() }
  $value = "$value".Trim()
  if ($value -eq '' -or $value -eq 'null') { return , [string[]]@() }
  return , [string[]]@($value -split ':' | Where-Object { $_ -ne '' })
}

<#
  Write the list, then read it back and prove it.

  This is not belt-and-braces. An earlier version wrote the list and assumed it had worked, and the
  device ended up with the key empty and accessibility_enabled=0 — which silently turned off the
  OWNER'S OTHER SERVICE, a password manager, not just this app's. Anything that edits a shared system
  setting has to verify, and has to refuse rather than proceed when the readback disagrees.
#>
function Set-Enabled([string[]] $services) {
  $expected = @($services | Where-Object { $_ -ne '' })
  $value = ($expected -join ':')

  if ($value -eq '') {
    # Removing the last service is legitimate, but it is also indistinguishable from having wiped the
    # list by accident, so say so rather than do it quietly.
    Write-Warning 'That would leave NO accessibility services enabled. Doing it, but check that is what you wanted.'
    & adb @adb shell settings put secure enabled_accessibility_services '""' | Out-Null
  }
  else {
    & adb @adb shell settings put secure enabled_accessibility_services $value | Out-Null
    & adb @adb shell settings put secure accessibility_enabled 1 | Out-Null
  }

  Start-Sleep -Milliseconds 400
  $actual = Get-Enabled
  $missing = @($expected | Where-Object { $actual -notcontains $_ })
  if ($missing.Count -gt 0) {
    throw "The device did not keep what was written. Missing: $($missing -join ', '). Now enabled: $($actual -join ', ')"
  }
}

$enabled = Get-Enabled

switch ($Action) {
  'on' {
    if ($enabled -contains $component) {
      Write-Host 'Selection Pad is already enabled.'
    }
    else {
      Set-Enabled (@($enabled) + @($component))
      Write-Host 'Selection Pad enabled. The pad appears as soon as the service connects.'
    }
  }
  'off' {
    if ($enabled -notcontains $component) {
      Write-Host 'Selection Pad is already off.'
    }
    else {
      Set-Enabled (@($enabled | Where-Object { $_ -ne $component }))
      Write-Host 'Selection Pad disabled.'
    }
  }
  'status' {
    $state = if ($enabled -contains $component) { 'ON' } else { 'off' }
    Write-Host "Selection Pad: $state"
    Write-Host 'Other accessibility services left alone:'
    $enabled | Where-Object { $_ -ne $component } | ForEach-Object { Write-Host "  $_" }
  }
}
