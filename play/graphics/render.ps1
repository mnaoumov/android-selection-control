<#
.SYNOPSIS
Renders the store graphics' SVG sources to the PNGs the Play Console asks for.

.DESCRIPTION
Uses a headless Chrome (or Edge) screenshot, so it needs no image toolchain. The PNGs are written
next to the sources and are NOT committed: the SVGs are the source of truth, and the PNGs are
rebuilt from them whenever the listing is updated.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$browser = @(
  "$env:ProgramFiles\Google\Chrome\Application\chrome.exe"
  "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe"
) | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $browser) { throw 'Neither Chrome nor Edge was found.' }

$graphics = @(
  @{ Source = 'icon.svg'; Width = 512; Height = 512 }
  @{ Source = 'feature-graphic.svg'; Width = 1024; Height = 500 }
)

foreach ($graphic in $graphics) {
  $source = Join-Path $PSScriptRoot $graphic.Source
  $target = [IO.Path]::ChangeExtension($source, '.png')
  $uri = ([Uri]$source).AbsoluteUri
  & $browser --headless --disable-gpu --hide-scrollbars --force-device-scale-factor=1 `
    "--window-size=$($graphic.Width),$($graphic.Height)" "--screenshot=$target" $uri 2>$null | Out-Null
  if (-not (Test-Path $target)) { throw "Rendering $($graphic.Source) produced nothing." }
  Write-Output "$target ($($graphic.Width)x$($graphic.Height))"
}
