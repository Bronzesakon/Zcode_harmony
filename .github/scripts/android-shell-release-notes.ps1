$ErrorActionPreference = 'Stop'

# Release notes for the android-shell APK, taken from the "## [X.Y.Z]" section of
# android-shell/CHANGELOG.md. The Flutter sibling project keeps its own copy of
# this script next to its own changelog; both exist so that a release fails loudly
# when the changelog section is missing rather than shipping an empty body.

$version = $env:GITHUB_REF_NAME -replace '^v', ''
$heading = "## [$version]"
$changelog = 'android-shell/CHANGELOG.md'

if (-not (Test-Path -LiteralPath $changelog)) {
    throw "$changelog not found (run this from the repository root)"
}

$lines = Get-Content -LiteralPath $changelog -Encoding UTF8
$start = [Array]::IndexOf($lines, ($lines | Where-Object { $_.StartsWith($heading) } | Select-Object -First 1))

if ($start -lt 0) {
    throw "$changelog does not contain $heading"
}

$notes = [System.Collections.Generic.List[string]]::new()
for ($i = $start + 1; $i -lt $lines.Length; $i++) {
    if ($lines[$i].StartsWith('## [')) { break }
    $notes.Add($lines[$i])
}

$notesText = ($notes -join "`n").Trim()
if ([string]::IsNullOrWhiteSpace($notesText)) {
    throw "No release notes found for $version"
}

[System.IO.File]::WriteAllText(
    (Join-Path (Get-Location) 'release-notes.md'),
    $notesText + [Environment]::NewLine,
    [System.Text.UTF8Encoding]::new($false)
)
