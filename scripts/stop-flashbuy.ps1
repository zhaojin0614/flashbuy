[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Root = Split-Path -Parent $ScriptDir
Set-Location $Root

$StartupDir = Join-Path $Root 'target\startup'
$NginxHome = Join-Path $Root 'nginx-1.18.0'
$NginxExe = Join-Path $NginxHome 'nginx.exe'
$ComposeFile = Join-Path $Root 'docker\rocketmq\docker-compose.yml'
$RedisCli = 'D:\APPS\Redis\redis-cli.exe'

function Write-Step {
    param([string]$Message)
    Write-Host "[*] $Message" -ForegroundColor Cyan
}

function Write-Ok {
    param([string]$Message)
    Write-Host "[OK] $Message" -ForegroundColor Green
}

function Write-Warn {
    param([string]$Message)
    Write-Host "[!] $Message" -ForegroundColor Yellow
}

function Write-Fail {
    param([string]$Message)
    Write-Host "[X] $Message" -ForegroundColor Red
}

function Test-TcpPort {
    param(
        [int]$Port,
        [string]$ComputerName = '127.0.0.1',
        [int]$TimeoutMs = 800
    )
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $task = $client.ConnectAsync($ComputerName, $Port)
        if (-not $task.Wait($TimeoutMs)) {
            return $false
        }
        return $client.Connected
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Wait-TcpPortDown {
    param(
        [int]$Port,
        [int]$TimeoutSeconds = 15
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (-not (Test-TcpPort -Port $Port)) {
            return $true
        }
        Start-Sleep -Milliseconds 300
    }
    return $false
}

try {
    Write-Host '========================================' -ForegroundColor Cyan
    Write-Host ' FlashBuy 一键停止' -ForegroundColor Cyan
    Write-Host '========================================' -ForegroundColor Cyan
    Write-Host 'MySQL 不在本脚本管理范围内，不会停止 MySQL。'
    Write-Host ''

    Write-Step '1/4 停止后端 (8081)'
    $backendStopped = $false
    $backendPidFile = Join-Path $StartupDir 'backend.pid'
    if (Test-Path $backendPidFile) {
        $backendPid = 0
        [int]::TryParse((Get-Content $backendPidFile -Raw).Trim(), [ref]$backendPid) | Out-Null
        if ($backendPid -gt 0) {
            $backendProcess = Get-Process -Id $backendPid -ErrorAction SilentlyContinue
            if ($backendProcess) {
                Stop-Process -Id $backendPid -Force
                $backendStopped = $true
            }
        }
        Remove-Item $backendPidFile -ErrorAction SilentlyContinue
    }

    if (-not $backendStopped) {
        $javaProcesses = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue |
            Where-Object { $_.CommandLine -and $_.CommandLine -like '*flashbuy-0.0.1-SNAPSHOT.jar*' }
        foreach ($javaProcess in $javaProcesses) {
            Stop-Process -Id $javaProcess.ProcessId -Force
            $backendStopped = $true
        }
    }

    if ($backendStopped) {
        Wait-TcpPortDown -Port 8081 -TimeoutSeconds 15 | Out-Null
        Write-Ok '后端已停止'
    } else {
        Write-Warn '未发现由脚本启动的后端进程'
    }

    Write-Step '2/4 停止 nginx (8080)'
    if (Test-TcpPort -Port 8080) {
        if (Test-Path $NginxExe) {
            Push-Location $NginxHome
            try {
                & $NginxExe -s quit 2>$null
            } finally {
                Pop-Location
            }
        }
        if (Wait-TcpPortDown -Port 8080 -TimeoutSeconds 15) {
            Write-Ok 'nginx 已停止'
        } else {
            Write-Warn 'nginx 未在 15 秒内停止，请检查 nginx 进程'
        }
    } else {
        Write-Warn 'nginx 未运行'
    }
    Remove-Item (Join-Path $StartupDir 'nginx.pid') -ErrorAction SilentlyContinue

    Write-Step '3/4 停止 RocketMQ'
    if (Get-Command docker.exe -ErrorAction SilentlyContinue) {
        & docker info *> $null
        if ($LASTEXITCODE -eq 0) {
            & docker compose -f $ComposeFile stop
            if ($LASTEXITCODE -eq 0) {
                Write-Ok 'RocketMQ 容器已停止'
            } else {
                Write-Warn 'RocketMQ compose stop 返回失败，请检查 docker compose ps'
            }
        } else {
            Write-Warn 'Docker Desktop 未运行，跳过 RocketMQ'
        }
    } else {
        Write-Warn '未找到 docker，跳过 RocketMQ'
    }

    Write-Step '4/4 停止 Redis (6379)'
    if (Test-TcpPort -Port 6379) {
        if (Test-Path $RedisCli) {
            & $RedisCli -h 127.0.0.1 -p 6379 shutdown 2>$null
        }
        if (Wait-TcpPortDown -Port 6379 -TimeoutSeconds 15) {
            Write-Ok 'Redis 已停止'
        } else {
            Write-Warn 'Redis 未在 15 秒内停止，请检查进程'
        }
    } else {
        Write-Warn 'Redis 未运行'
    }
    Remove-Item (Join-Path $StartupDir 'redis.pid') -ErrorAction SilentlyContinue

    Write-Host ''
    Write-Host '========================================' -ForegroundColor Green
    Write-Host ' 停止流程结束（MySQL 未操作）' -ForegroundColor Green
    Write-Host '========================================' -ForegroundColor Green
    Write-Host ''
} catch {
    Write-Host ''
    Write-Fail $_.Exception.Message
    exit 1
}
