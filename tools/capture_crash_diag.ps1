# capture_crash_diag.ps1
# 客户端疑似 (1,1,2) 崩溃时，抓取最近的服务端/客户端/GC/线程现场。
# 用法:
#   powershell -NoProfile -ExecutionPolicy Bypass -File capture_crash_diag.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File capture_crash_diag.ps1 -JavaPid 1234
param(
    [string]$ServerDir = 'E:\YSPrivateServer\Server\LunaGC',
    [string]$ClientLog = "$env:USERPROFILE\AppData\LocalLow\miHoYo\Genshin Impact\output_log.txt",
    [int]$JavaPid = 0,
    [int]$TailLines = 500
)

$ErrorActionPreference = 'SilentlyContinue'
$now = Get-Date
$stamp = $now.ToString('yyyyMMdd-HHmmss')
$outDir = Join-Path $ServerDir ('crash-diagnostics\' + $stamp)
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

function Save-Tail {
    param([string]$Name, [string]$Path, [int]$Lines = $TailLines)
    if (-not $Path -or -not (Test-Path -LiteralPath $Path)) { return }
    $dest = Join-Path $outDir $Name
    try {
        Get-Content -LiteralPath $Path -Tail $Lines | Out-File -LiteralPath $dest -Encoding utf8
        Write-Output "saved $Name"
    } catch {
        Write-Output "failed $Name : $($_.Exception.Message)"
    }
}

# 找 LunaGC java 进程
if ($JavaPid -le 0) {
    $proc = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -like '*LunaGC-6.6.0.jar*' } |
        Select-Object -First 1
    if ($proc) { $JavaPid = [int]$proc.ProcessId }
}

$summary = New-Object System.Text.StringBuilder
[void]$summary.AppendLine("Crash diagnostic captured: $($now.ToString('yyyy-MM-dd HH:mm:ss'))")
[void]$summary.AppendLine("ServerDir: $ServerDir")
[void]$summary.AppendLine("JavaPid: $JavaPid")
[void]$summary.AppendLine("ClientLog: $ClientLog")
[void]$summary.AppendLine("OutputDir: $outDir")
[void]$summary.AppendLine("")

# 1. 服务端日志
Save-Tail 'server-run-tail.log' (Join-Path $ServerDir 'server-run.log')
Save-Tail 'server-run-err-tail.log' (Join-Path $ServerDir 'server-run.log.err')
Save-Tail 'gc-tail.log' (Join-Path $ServerDir 'gc.log')
Save-Tail 'server-monitor.log' (Join-Path $ServerDir 'server-monitor.log') -Lines 2000

# 2. 客户端日志（Unity output_log）
if (Test-Path -LiteralPath $ClientLog) {
    Save-Tail 'client-output-tail.log' $ClientLog -Lines 1500
    $clientErrorLines = Get-Content -LiteralPath $ClientLog -Tail 5000 |
        Where-Object { $_ -match '(?i)(ArgumentOutOfRangeException|IndexOutOfRangeException|NullReferenceException|StackOverflowException|AccessViolation|UnhandledException|Fatal|ERROR|Exception)' }
    if ($clientErrorLines) {
        $clientErrorLines | Out-File -LiteralPath (Join-Path $outDir 'client-errors.log') -Encoding utf8
        Write-Output 'saved client-errors.log'
    }
}

# 3. 服务端错误/异常关键字
$serverAll = if (Test-Path (Join-Path $ServerDir 'server-run.log')) { Get-Content (Join-Path $ServerDir 'server-run.log') } else { @() }
$serverErrors = $serverAll | Where-Object { $_ -match '(?i)(ERROR|EXCEPTION|LuaError|EnetPingTimeout|BLOCKED|DEADLOCK|OutOfMemory)' }
if ($serverErrors) {
    $serverErrors | Select-Object -Last 500 | Out-File -LiteralPath (Join-Path $outDir 'server-errors.log') -Encoding utf8
    [void]$summary.AppendLine("server error lines: $($serverErrors.Count)")
    Write-Output 'saved server-errors.log'
}

# 4. 线程 dump（jstack，找 BLOCKED）
if ($JavaPid -gt 0) {
    $jstack = 'C:\jdk-21.0.2\bin\jstack.exe'
    if (-not (Test-Path $jstack)) { $jstack = Join-Path $env:JAVA_HOME 'bin\jstack.exe' }
    if ($jstack -and (Test-Path $jstack)) {
        $dump = & $jstack -l $JavaPid 2>&1
        if ($dump) {
            $dump | Out-File -LiteralPath (Join-Path $outDir 'jstack.log') -Encoding utf8
            $blocked = $dump | Where-Object { $_ -match 'BLOCKED|deadlock|Deadlock|waiting to lock' }
            if ($blocked) {
                $blocked | Out-File -LiteralPath (Join-Path $outDir 'jstack-blocked.log') -Encoding utf8
                [void]$summary.AppendLine("blocked thread lines: $($blocked.Count)")
            }
            Write-Output 'saved jstack.log'
        }
    }

    $p = Get-Process -Id $JavaPid
    if ($p) {
        [void]$summary.AppendLine("Process: $($p.ProcessName) CPU=$($p.CPU) WS_MB=$([math]::Round($p.WorkingSet64/1MB,1)) StartTime=$($p.StartTime)")
    }
}

# 5. 端口占用
$ports = Get-NetTCPConnection -LocalPort 8088,22101 -ErrorAction SilentlyContinue |
    Select-Object LocalAddress,LocalPort,State,OwningProcess
if ($ports) {
    $ports | Out-File -LiteralPath (Join-Path $outDir 'ports.log') -Encoding utf8
    [void]$summary.AppendLine("ports: $($ports.Count) entries")
}

$summary.ToString() | Out-File -LiteralPath (Join-Path $outDir 'SUMMARY.txt') -Encoding utf8
Write-Output '--- SUMMARY ---'
Write-Output $summary.ToString()
Write-Output "Diagnostic dir: $outDir"
