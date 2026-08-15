# monitor_client_crash.ps1
# 监听客户端 output_log.txt，一旦出现疑似崩溃异常，自动执行 capture_crash_diag.ps1
# 抓取最近的服务端/客户端/GC/jstack 诊断现场。
# 可与 monitor_server_log.ps1 同时运行。
param(
    [int]$TargetServerPid = 0,
    [string]$LogDir = 'E:\YSPrivateServer\Server\LunaGC',
    [string]$ClientLog = $null,
    [string]$MonitorLog = 'E:\YSPrivateServer\Server\LunaGC\client-crash-monitor.log',
    [int]$CooldownSeconds = 120
)
if ([string]::IsNullOrEmpty($ClientLog)) {
    $ClientLog = Join-Path $env:USERPROFILE 'AppData\LocalLow\miHoYo\Genshin Impact\output_log.txt'
}
$ErrorActionPreference = 'SilentlyContinue'
$pattern = '(?i)(ArgumentOutOfRangeException|IndexOutOfRangeException|NullReferenceException|StackOverflowException|AccessViolation|Fatal error in GC)'
$offset = 0
# 启动时只从当前文件末尾开始监视，避免把历史异常当成新崩溃。
if (Test-Path -LiteralPath $ClientLog) {
    $existing = Get-Content -LiteralPath $ClientLog -ErrorAction SilentlyContinue
    if ($null -ne $existing) { $offset = $existing.Count }
}
$lastCapture = [datetime]::MinValue
$diagScript = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) 'capture_crash_diag.ps1'

function Add-Monitor {
    param([string]$Msg)
    $line = "[$((Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))] $Msg"
    $line | Out-File -LiteralPath $MonitorLog -Append -Encoding utf8
}

function Read-NewLines {
    param([string]$Path, [ref]$Offset)
    if (-not (Test-Path -LiteralPath $Path)) { return @() }
    $all = Get-Content -LiteralPath $Path -ErrorAction SilentlyContinue
    if ($null -eq $all) { return @() }
    if ($all.Count -lt $Offset.Value) {
        # output_log.txt 被客户端重启覆盖了，重头读
        $Offset.Value = 0
    }
    $lines = @()
    if ($all.Count -gt $Offset.Value) {
        $lines = $all[$Offset.Value..($all.Count - 1)]
    }
    $Offset.Value = $all.Count
    return $lines
}

if ($TargetServerPid -le 0) {
    $proc = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -like '*LunaGC-6.6.0.jar*' } |
        Select-Object -First 1
    if ($proc) { $TargetServerPid = [int]$proc.ProcessId }
}

Add-Monitor "MONITOR_START serverPid=$TargetServerPid"
while ($true) {
    if ($TargetServerPid -gt 0 -and -not (Get-Process -Id $TargetServerPid -ErrorAction SilentlyContinue)) {
        Add-Monitor "PROCESS_EXIT serverPid=$TargetServerPid"
        break
    }

    foreach ($line in (Read-NewLines $ClientLog ([ref]$offset))) {
        if ($line -match $pattern) {
            Add-Monitor "CLIENT_CRASH_SIGNATURE: $line"
            $now = Get-Date
            if (($now - $lastCapture).TotalSeconds -ge $CooldownSeconds) {
                $lastCapture = $now
                Add-Monitor "Running crash diagnostic capture..."
                $out = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $diagScript -JavaPid $TargetServerPid 2>&1
                foreach ($o in $out) { Add-Monitor "[DIAG] $o" }
            }
        }
    }

    Start-Sleep -Seconds 2
}
Add-Monitor "MONITOR_STOP"
