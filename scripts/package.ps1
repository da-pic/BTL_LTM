param([switch]$SkipBuild, [string]$ArtifactName = 'bomb-it-multiplayer.jar', [switch]$NoRuntime)
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
# Goi cho nguoi choi chi giu thanh phan can chay, khong tron tai lieu/bao cao cu vao ban demo.
foreach ($launcher in @('CHOI_GAME.cmd','MAY_CHU.cmd')) {
    Copy-Item -LiteralPath (Join-Path $repoRoot $launcher) -Destination $folder
}
foreach ($script in @('run-server.cmd','run-client.cmd','java-env.cmd','classpath.cmd')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $script) -Destination (Join-Path $folder 'scripts')
}
if (-not $NoRuntime) {
    $linker = if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/jlink.exe'))) {
        Join-Path $env:JAVA_HOME 'bin/jlink.exe'
    } else {
        $command = Get-Command 'jlink.exe' -ErrorAction SilentlyContinue
        if (-not $command) { throw 'JDK 25 with jlink is required for the bundled demo. Use -NoRuntime only for machines with Java 25 installed.' }
        $command.Source
    }
    $jdkRoot = Split-Path -Parent (Split-Path -Parent $linker)
    $runtime = Join-Path $folder 'runtime'
    # Module desktop bao gom Swing/AWT/ImageIO; java.base cung cap TCP va TLS nen.
    & $linker --module-path (Join-Path $jdkRoot 'jmods') --add-modules java.base,java.desktop,java.logging `
        --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output $runtime
    if ($LASTEXITCODE -ne 0) { throw "jlink failed ($LASTEXITCODE)" }
    & (Join-Path $runtime 'bin/java.exe') -cp (Join-Path $folder 'bomb-it-multiplayer.jar') com.bombit.client.app.ClientApplication --check-runtime
    if ($LASTEXITCODE -ne 0) { throw 'Bundled runtime cannot load the game' }
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
