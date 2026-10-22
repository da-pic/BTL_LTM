param([switch]$SkipBuild, [string]$ArtifactName = 'bomb-it-multiplayer.jar')
$ErrorActionPreference = 'Stop'
if ($ArtifactName -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]*\.jar$') { throw 'ArtifactName must be a JAR file name without directory components' }
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $SkipBuild) { & (Join-Path $PSScriptRoot 'build.ps1') -ArtifactName $ArtifactName }
$artifact = Join-Path (Join-Path $repoRoot 'target') $ArtifactName
if (-not (Test-Path -LiteralPath $artifact)) { throw 'Build the JAR first' }
$releaseRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot 'target/release'))
$folder = [IO.Path]::GetFullPath((Join-Path $releaseRoot 'bomb-it-multiplayer'))
if (-not $folder.StartsWith($releaseRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe release output path' }
if (Test-Path -LiteralPath $folder) { Remove-Item -LiteralPath $folder -Recurse -Force }
New-Item -ItemType Directory -Force -Path (Join-Path $folder 'scripts') | Out-Null
Copy-Item -LiteralPath $artifact -Destination (Join-Path $folder 'bomb-it-multiplayer.jar')
$infoName = if ($ArtifactName -eq 'bomb-it-multiplayer.jar') { 'BUILD_INFO.txt' } else { $ArtifactName + '.BUILD_INFO.txt' }
Copy-Item -LiteralPath (Join-Path (Join-Path $repoRoot 'target') $infoName) -Destination (Join-Path $folder 'BUILD_INFO.txt')
Copy-Item -LiteralPath (Join-Path $repoRoot 'README.md') -Destination $folder
Copy-Item -LiteralPath (Join-Path $repoRoot 'PROTOCOL.md') -Destination $folder
Copy-Item -LiteralPath (Join-Path $repoRoot 'docs') -Destination $folder -Recurse
foreach ($script in @('run-server.cmd','run-client.cmd','run-benchmark.cmd','java-env.cmd','classpath.cmd')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $script) -Destination (Join-Path $folder 'scripts')
}
$zip = Join-Path $releaseRoot 'bomb-it-multiplayer.zip'
# Write standard forward-slash ZIP entries also when packaging on Windows PowerShell 5.
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$zipStream = [IO.File]::Open($zip, [IO.FileMode]::Create, [IO.FileAccess]::Write, [IO.FileShare]::None)
$archive = [IO.Compression.ZipArchive]::new($zipStream, [IO.Compression.ZipArchiveMode]::Create, $false)
try {
    foreach ($file in (Get-ChildItem -LiteralPath $folder -Recurse -File)) {
        $entryName = 'bomb-it-multiplayer/' + $file.FullName.Substring($folder.Length + 1).Replace('\','/')
        $null = [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file.FullName, $entryName)
    }
} finally { $archive.Dispose() }
$hash = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash
[IO.File]::WriteAllText(($zip + '.sha256'), ($hash + '  bomb-it-multiplayer.zip' + "`n"), [Text.UTF8Encoding]::new($false))
Write-Output "Packaged $zip SHA256=$hash"
