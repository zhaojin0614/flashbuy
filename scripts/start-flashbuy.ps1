[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$SkipBackend,
    [switch]$SkipDockerDesktopStart
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Root = Split-Path -Parent $ScriptDir
Set-Location $Root

$StartupDir = Join-Path $Root 'target\startup'
New-Item -ItemType Directory -Force -Path $StartupDir | Out-Null

$JarPath = Join-Path $Root 'target\flashbuy-0.0.1-SNAPSHOT.jar'
$ComposeFile = Join-Path $Root 'docker\rocketmq\docker-compose.yml'
$NginxHome = Join-Path $Root 'nginx-1.18.0'
$NginxExe = Join-Path $NginxHome 'nginx.exe'
$RedisHome = 'D:\APPS\Redis'
$RedisServer = Join-Path $RedisHome 'redis-server.exe'
$RedisCli = Join-Path $RedisHome 'redis-cli.exe'
$HealthUrl = 'http://127.0.0.1:8081/actuator/health'
$FrontUrl = 'http://127.0.0.1:8080/'
$DockerDesktop = 'C:\Program Files\Docker\Docker\Docker Desktop.exe'

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

function Wait-TcpPort {
    param(
        [int]$Port,
        [int]$TimeoutSeconds = 30,
        [string]$Name = 'service'
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-TcpPort -Port $Port) {
            return $true
        }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function Test-HttpOk {
    param([string]$Url)
    try {
        $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3
        return ($response.StatusCode -eq 200)
    } catch {
        return $false
    }
}

function Wait-HttpOk {
    param(
        [string]$Url,
        [int]$TimeoutSeconds = 60
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-HttpOk -Url $Url) {
            return $true
        }
        Start-Sleep -Seconds 1
    }
    return $false
}

function Test-RedisPong {
    if (-not (Test-Path $RedisCli)) {
        return $false
    }
    try {
        $result = & $RedisCli -h 127.0.0.1 -p 6379 ping 2>$null
        return (($result | Out-String).Trim() -eq 'PONG')
    } catch {
        return $false
    }
}

function Test-DockerDaemon {
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & docker info *> $null
        return ($LASTEXITCODE -eq 0)
    } catch {
        return $false
    } finally {
        $ErrorActionPreference = $previousErrorAction
    }
}

function Wait-DockerDaemon {
    param([int]$TimeoutSeconds = 120)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-DockerDaemon) {
            return $true
        }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Resolve-Java {
    $candidates = @()
    if ($env:JAVA_HOME) {
        $candidates += (Join-Path $env:JAVA_HOME 'bin\java.exe')
    }
    $candidates += 'D:\APPS\java21\bin\java.exe'
    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($javaCommand) {
        $candidates += $javaCommand.Source
    }

    # java -version 会写到 stderr；Windows PowerShell 5.1 在 Stop 模式下会把它当异常
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        foreach ($candidate in $candidates) {
            if ($candidate -and (Test-Path $candidate)) {
                $version = (& $candidate -version 2>&1 | Out-String)
                if ($version -match 'version "21') {
                    return $candidate
                }
            }
        }
    } finally {
        $ErrorActionPreference = $previousErrorAction
    }
    throw '未找到 JDK 21。请设置 JAVA_HOME 指向 JDK 21，或确认 D:\APPS\java21\bin\java.exe 存在。'
}

function Resolve-Maven {
    $mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if (-not $mavenCommand) {
        $mavenCommand = Get-Command mvn -ErrorAction SilentlyContinue
    }
    if (-not $mavenCommand) {
        throw '未找到 Maven。请确认 mvn 在 PATH 中，或先手动构建 jar。'
    }
    return $mavenCommand.Source
}

function Test-NeedsBuild {
    if (-not (Test-Path $JarPath)) {
        return $true
    }
    $jarTime = (Get-Item $JarPath).LastWriteTimeUtc
    $items = @()
    $srcDir = Join-Path $Root 'src'
    if (Test-Path $srcDir) {
        $items += Get-ChildItem -Path $srcDir -Recurse -File
    }
    $items += Get-Item (Join-Path $Root 'pom.xml')
    $latest = $items | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    return ($latest -and $latest.LastWriteTimeUtc -gt $jarTime)
}

function Show-LogTail {
    param(
        [string]$Path,
        [int]$Lines = 30
    )
    if (Test-Path $Path) {
        Write-Host "--- $Path (last $Lines lines) ---" -ForegroundColor DarkGray
        Get-Content -Path $Path -Tail $Lines
    }
}

try {
    Write-Host '========================================' -ForegroundColor Cyan
    Write-Host ' FlashBuy 一键启动' -ForegroundColor Cyan
    Write-Host '========================================' -ForegroundColor Cyan
    Write-Host "项目目录: $Root"
    Write-Host ''

    Write-Step '1/5 检查 MySQL (3306)，脚本不会启动 MySQL'
    if (-not (Test-TcpPort -Port 3306)) {
        throw 'MySQL 3306 未启动。请先手动启动 MySQL，再重新运行本脚本。'
    }
    Write-Ok 'MySQL 已启动'
    if (-not (Test-Path (Join-Path $Root 'application-local.yaml'))) {
        Write-Warn '未找到 application-local.yaml，将使用 application.yaml 中的默认配置'
    }

    Write-Step '2/5 检查 Redis (6379)'
    if (Test-TcpPort -Port 6379) {
        if (-not (Test-RedisPong)) {
            throw '6379 已被占用，但 Redis PING 失败。请检查该端口上的进程。'
        }
        Write-Ok 'Redis 已运行'
    } else {
        if (-not (Test-Path $RedisServer)) {
            throw "未找到 Redis: $RedisServer"
        }
        $redisOut = Join-Path $StartupDir 'redis.out.log'
        $redisErr = Join-Path $StartupDir 'redis.err.log'
        $redisProc = Start-Process -FilePath $RedisServer `
            -ArgumentList @('redis.conf') `
            -WorkingDirectory $RedisHome `
            -RedirectStandardOutput $redisOut `
            -RedirectStandardError $redisErr `
            -PassThru -WindowStyle Hidden
        if ($redisProc) {
            Set-Content -Path (Join-Path $StartupDir 'redis.pid') -Value $redisProc.Id -Encoding ASCII
        }
        if (-not (Wait-TcpPort -Port 6379 -TimeoutSeconds 15 -Name 'Redis')) {
            Show-LogTail -Path $redisErr
            throw 'Redis 启动超时。请查看 target\startup\redis.err.log。'
        }
        if (-not (Test-RedisPong)) {
            Show-LogTail -Path $redisErr
            throw 'Redis 已监听 6379，但 PING 失败。'
        }
        Write-Ok 'Redis 已启动'
    }

    Write-Step '3/5 检查 RocketMQ (9876/10911)'
    if ((Test-TcpPort -Port 9876) -and (Test-TcpPort -Port 10911)) {
        Write-Ok 'RocketMQ 已运行'
    } else {
        if (-not (Get-Command docker.exe -ErrorAction SilentlyContinue)) {
            throw '未找到 docker 命令，请先安装 Docker Desktop。'
        }
        if (-not (Test-DockerDaemon)) {
            if ($SkipDockerDesktopStart) {
                throw 'Docker Desktop 未运行；已使用 -SkipDockerDesktopStart，请手动启动 Docker Desktop。'
            }
            if (-not (Test-Path $DockerDesktop)) {
                throw 'Docker Desktop 未运行，且未找到 Docker Desktop.exe。请手动启动 Docker Desktop。'
            }
            Write-Warn 'Docker Desktop 未运行，尝试启动...'
            Start-Process -FilePath $DockerDesktop | Out-Null
            if (-not (Wait-DockerDaemon -TimeoutSeconds 120)) {
                throw 'Docker Desktop 启动超时，请手动启动 Docker Desktop 后重试。'
            }
        }

        if (-not (Test-Path $ComposeFile)) {
            throw "未找到 RocketMQ compose 文件: $ComposeFile"
        }
        & docker compose -f $ComposeFile up -d
        if ($LASTEXITCODE -ne 0) {
            throw 'docker compose up -d 启动 RocketMQ 失败。'
        }
        if (-not (Wait-TcpPort -Port 9876 -TimeoutSeconds 60 -Name 'RocketMQ NameServer')) {
            throw 'RocketMQ NameServer 9876 启动超时。'
        }
        if (-not (Wait-TcpPort -Port 10911 -TimeoutSeconds 90 -Name 'RocketMQ Broker')) {
            throw 'RocketMQ Broker 10911 启动超时，请检查 docker compose ps。'
        }
        Write-Ok 'RocketMQ 已启动'
    }

    Write-Step '4/5 检查后端 (8081)'
    if ($SkipBackend) {
        Write-Warn '已使用 -SkipBackend，跳过后端启动；请在 IDEA 中运行 FlashbuyApplication'
    } elseif (Test-TcpPort -Port 8081) {
        if (Test-HttpOk -Url $HealthUrl) {
            Write-Ok '后端已运行且健康检查通过'
        } else {
            throw '8081 已被占用，但 /actuator/health 不是 200。请检查端口占用。'
        }
    } else {
        if (-not $SkipBuild -and (Test-NeedsBuild)) {
            $maven = Resolve-Maven
            Write-Step "检测到源码比 jar 新，执行构建: $maven -B -DskipTests package"
            & $maven -B -DskipTests package
            if ($LASTEXITCODE -ne 0) {
                throw 'Maven 构建失败，后端未启动。'
            }
        }
        if (-not (Test-Path $JarPath)) {
            throw "未找到后端 jar: $JarPath。请先执行 mvn -B -DskipTests package，或去掉 -SkipBuild。"
        }

        $java = Resolve-Java
        $backendOut = Join-Path $StartupDir 'backend.out.log'
        $backendErr = Join-Path $StartupDir 'backend.err.log'
        $backendProc = Start-Process -FilePath $java `
            -ArgumentList @('-jar', $JarPath) `
            -WorkingDirectory $Root `
            -RedirectStandardOutput $backendOut `
            -RedirectStandardError $backendErr `
            -PassThru -WindowStyle Hidden
        if ($backendProc) {
            Set-Content -Path (Join-Path $StartupDir 'backend.pid') -Value $backendProc.Id -Encoding ASCII
        }
        if (-not (Wait-HttpOk -Url $HealthUrl -TimeoutSeconds 90)) {
            Show-LogTail -Path $backendOut -Lines 40
            Show-LogTail -Path $backendErr -Lines 40
            throw '后端健康检查超时。请查看 target\startup\backend.out.log / backend.err.log。'
        }
        Write-Ok '后端已启动，健康检查通过'
    }

    Write-Step '5/5 检查 nginx (8080)'
    if (Test-TcpPort -Port 8080) {
        if (Test-HttpOk -Url $FrontUrl) {
            Write-Ok 'nginx 已运行'
        } else {
            throw '8080 已被占用，但页面不是 200。请检查端口占用。'
        }
    } else {
        if (-not (Test-Path $NginxExe)) {
            throw "未找到 nginx: $NginxExe"
        }
        $nginxProc = Start-Process -FilePath $NginxExe `
            -WorkingDirectory $NginxHome `
            -PassThru -WindowStyle Hidden
        if ($nginxProc) {
            Set-Content -Path (Join-Path $StartupDir 'nginx.pid') -Value $nginxProc.Id -Encoding ASCII
        }
        if (-not (Wait-HttpOk -Url $FrontUrl -TimeoutSeconds 15)) {
            throw 'nginx 启动超时。请检查 nginx 错误日志和 8080 端口。'
        }
        Write-Ok 'nginx 已启动'
    }

    Write-Host ''
    Write-Host '========================================' -ForegroundColor Green
    Write-Host ' 启动完成' -ForegroundColor Green
    Write-Host '========================================' -ForegroundColor Green
    Write-Host "前端: $FrontUrl"
    Write-Host "后端健康检查: $HealthUrl"
    Write-Host "日志目录: $StartupDir"
    Write-Host '停止服务: scripts\stop-flashbuy.cmd'
    Write-Host ''
} catch {
    Write-Host ''
    Write-Fail $_.Exception.Message
    Write-Host "启动未完成。日志目录: $StartupDir" -ForegroundColor Yellow
    exit 1
}
