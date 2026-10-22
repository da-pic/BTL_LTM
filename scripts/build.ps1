param([string]$ArtifactName = 'bomb-it-multiplayer.jar')
$ErrorActionPreference = 'Stop'
if ($ArtifactName -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]*\.jar$') { throw 'ArtifactName must be a JAR file name without directory components' }
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$buildRoot = Join-Path $repoRoot 'target'
$classes = [IO.Path]::GetFullPath((Join-Path $buildRoot 'demo-classes'))
if (-not $classes.StartsWith($buildRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe build output path' }
function Find-JdkTool([string]$name) {
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME "bin/$name.exe"
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    $command = Get-Command "$name.exe" -ErrorAction SilentlyContinue
    if (-not $command) { throw "JDK 25+ required: $name is missing. Set JAVA_HOME or put the JDK bin directory on PATH." }
    return $command.Source
}
$javac = Find-JdkTool 'javac'
$jar = Find-JdkTool 'jar'
if (Test-Path -LiteralPath $classes) { Remove-Item -LiteralPath $classes -Recurse -Force }
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $repoRoot 'src/main/java') -Recurse -Filter '*.java' | Sort-Object FullName | ForEach-Object { '"' + $_.FullName.Replace('\','/') + '"' })
$sourceList = Join-Path $buildRoot 'demo-sources.txt'
[IO.File]::WriteAllLines($sourceList, [string[]]$sources, [Text.UTF8Encoding]::new($false))
& $javac --release 25 -encoding UTF-8 -d $classes "@$sourceList"
if ($LASTEXITCODE -ne 0) { throw "javac failed ($LASTEXITCODE)" }
$resources = Join-Path $repoRoot 'src/main/resources'
if (Test-Path -LiteralPath $resources) { Get-ChildItem -LiteralPath $resources | Copy-Item -Destination $classes -Recurse -Force }
$artifact = Join-Path $buildRoot $ArtifactName
& $jar --create --file $artifact --main-class com.bombit.client.app.ClientApplication -C $classes .
if ($LASTEXITCODE -ne 0) { throw "jar failed ($LASTEXITCODE)" }
$revision = 'unknown'
if ((Get-Command git -ErrorAction SilentlyContinue) -and (Test-Path -LiteralPath (Join-Path $repoRoot '.git'))) {
    $gitSafeRoot = $repoRoot.Replace('\','/')
    $gitRevision = & git -c "safe.directory=$gitSafeRoot" -C $repoRoot rev-parse --short HEAD
    if ($LASTEXITCODE -ne 0) { throw 'Cannot determine source revision' }
    $revision = $gitRevision.Trim()
    if (@(& git -c "safe.directory=$gitSafeRoot" -C $repoRoot status --porcelain).Count -gt 0) { $revision += '-dirty' }
}
$info = "built_utc=$([DateTime]::UtcNow.ToString('o'))`nsource_revision=$revision`ntarget_java=25`njar_sha256=$((Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash)`n"
$infoName = if ($ArtifactName -eq 'bomb-it-multiplayer.jar') { 'BUILD_INFO.txt' } else { $ArtifactName + '.BUILD_INFO.txt' }
[IO.File]::WriteAllText((Join-Path $buildRoot $infoName), $info, [Text.UTF8Encoding]::new($false))
Write-Output "Built $artifact ($revision, Java target 25)"
