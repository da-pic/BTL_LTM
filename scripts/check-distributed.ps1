param([string]$RuntimeRoot = (Join-Path $PSScriptRoot '..'),
      [string]$OutputDirectory = (Join-Path $PSScriptRoot '../target/distributed-smoke'))
$ErrorActionPreference = 'Stop'
$runtime = [IO.Path]::GetFullPath($RuntimeRoot)
$output = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $output | Out-Null
$java = if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
    Join-Path $env:JAVA_HOME 'bin/java.exe'
} else { (Get-Command java.exe -ErrorAction Stop).Source }
$jar = Join-Path $runtime 'bomb-it-multiplayer.jar'
if (-not (Test-Path -LiteralPath $jar)) { $jar = Join-Path $runtime 'target/bomb-it-multiplayer.jar' }
if (-not (Test-Path -LiteralPath $jar)) { throw 'Build/package the JAR first' }
$run = 'distributed-' + [Guid]::NewGuid().ToString()
$owned = [Collections.Generic.List[Diagnostics.Process]]::new()
function Start-Java([string[]]$JavaArguments, [string]$Name) {
    # Quote each argument for the native Windows command line, including paths with spaces.
    if (@($JavaArguments | Where-Object { $_.Contains('"') }).Count) { throw 'Quotes in arguments are unsupported' }
    $quoted = @($JavaArguments | ForEach-Object { '"' + $_ + '"' })
    $process = Start-Process -FilePath $java -ArgumentList $quoted -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $output "$run-$Name.log") `
        -RedirectStandardError (Join-Path $output "$run-$Name.err")
    $owned.Add($process)
    # Retain the native handle so Windows PowerShell can retrieve ExitCode after a fast exit.
    $null = $process.Handle
    return $process
}
function Wait-Log([Diagnostics.Process]$Process, [string]$Name, [string]$Pattern) {
    $until = [DateTime]::UtcNow.AddSeconds(20)
    while ([DateTime]::UtcNow -lt $until) {
        $log = Join-Path $output "$run-$Name.log"
        if (Test-Path -LiteralPath $log) {
            $stream = [IO.File]::Open($log, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
            $reader = [IO.StreamReader]::new($stream)
            try { $match = [regex]::Match($reader.ReadToEnd(), $Pattern) }
            finally { $reader.Dispose() }
            if ($match.Success) { return $match.Groups[1].Value }
        }
        if ($Process.HasExited) { throw "$Name exited early; inspect $output" }
        Start-Sleep -Milliseconds 100
    }
    throw "Timed out waiting for $Name; inspect $output"
}
try {
    $server = Start-Java @('-cp',$jar,'com.bombit.server.app.ServerApplication','0') 'server'
    $port = Wait-Log $server 'server' 'Lobby server: port=(\d+)'
    $common = @('-cp',$jar,'com.bombit.tools.NetworkBenchmark','--host','127.0.0.1','--port',$port,
        '--clients','1','--expected-clients','2','--mode','movement','--seconds','2','--warmup','1',
        '--label','same-host-separate-processes','--revision','runtime-smoke','--run-id',$run)
    $hostCsv = Join-Path $output "$run-host.csv"
    $guestCsv = Join-Path $output "$run-guest.csv"
    $hostRunner = Start-Java ($common + @('--csv',$hostCsv)) 'host'
    $room = Wait-Log $hostRunner 'host' 'ROOM_ID=([0-9a-f-]+)'
    $guestRunner = Start-Java ($common + @('--room-id',$room,'--csv',$guestCsv)) 'guest'
    foreach ($runner in @($hostRunner,$guestRunner)) {
        if (-not $runner.WaitForExit(20000)) { throw 'Runner timed out' }
        $runner.Refresh()
        if ($runner.ExitCode -ne 0) { throw "Runner failed; inspect $output" }
    }
    foreach ($csv in @($hostCsv,$guestCsv)) {
        $rows = @(Import-Csv -LiteralPath $csv)
        if ($rows.Count -ne 1 -or $rows[0].status -ne 'PASS' -or $rows[0].cleanup_ok -ne 'true' `
            -or [int]$rows[0].move_ack_samples -lt 1) { throw "Invalid smoke result: $csv" }
    }
    Write-Output "PASS: separate server + host/guest Java processes; CSV/logs: $output"
} finally {
    foreach ($process in $owned) {
        if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
        $process.WaitForExit()
        $process.Dispose()
    }
}
