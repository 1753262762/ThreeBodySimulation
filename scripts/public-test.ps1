[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('Start', 'Status', 'Logs', 'Stop')]
    [string]$Action = 'Status',

    [switch]$Force
)

$ErrorActionPreference = 'Stop'

$projectName = 'threebody-public-test'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $repositoryRoot 'docker-compose.public-test.yml'
$localHealthUrl = 'http://127.0.0.1:18721/api/v1/presets'
$quickTunnelPattern = 'https://[a-z0-9-]+\.trycloudflare\.com'
$composePrefix = @('compose', '-p', $projectName, '-f', $composeFile)

function Assert-DockerReady {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw '未找到 Docker CLI。请先安装 Docker Desktop 或 Docker Engine。'
    }

    & docker info --format '{{.ServerVersion}}' 1>$null 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw '无法连接 Docker Engine。请先启动 Docker Desktop，然后重试。'
    }

    & docker compose version 1>$null 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw '当前 Docker CLI 不支持 Compose V2（docker compose）。'
    }
}

function Invoke-Compose {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,

        [switch]$Capture
    )

    $dockerArguments = $composePrefix + $Arguments
    if ($Capture) {
        $output = & docker @dockerArguments 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw ($output -join [Environment]::NewLine)
        }
        return $output
    }

    & docker @dockerArguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose 执行失败，退出码：$LASTEXITCODE"
    }
}

function Get-ProjectContainerIds {
    $output = Invoke-Compose -Arguments @('ps', '-a', '-q') -Capture
    return @($output | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}

function Test-LocalHealth {
    try {
        $response = Invoke-WebRequest -Uri $localHealthUrl -UseBasicParsing -TimeoutSec 5
        return $response.StatusCode -ge 200 -and $response.StatusCode -lt 300
    }
    catch {
        return $false
    }
}

function Get-PublicUrl {
    try {
        $logs = Invoke-Compose -Arguments @('logs', '--no-color', 'tunnel') -Capture
        $match = [regex]::Match(($logs -join [Environment]::NewLine), $quickTunnelPattern)
        if ($match.Success) {
            return $match.Value
        }
    }
    catch {
        return $null
    }

    return $null
}

function Show-RelevantLogs {
    Write-Host ''
    Write-Host '最近的应用与 Tunnel 日志：' -ForegroundColor Yellow
    try {
        Invoke-Compose -Arguments @('logs', '--no-color', '--tail', '100', 'app', 'tunnel')
    }
    catch {
        Write-Warning "无法读取日志：$($_.Exception.Message)"
    }
}

function Remove-PublicTestProject {
    try {
        Invoke-Compose -Arguments @('down', '-v', '--remove-orphans')
    }
    catch {
        Write-Warning "临时项目清理失败，请手动执行：docker compose -p $projectName -f `"$composeFile`" down -v --remove-orphans"
    }
}

function Start-PublicTest {
    Assert-DockerReady

    if ((Get-ProjectContainerIds).Count -gt 0) {
        throw "临时项目 $projectName 已存在。请先运行 Status 查看状态，或运行 Stop 停止后再重新启动。"
    }

    $startedByThisRun = $false
    try {
        Write-Host "正在构建并启动临时项目 $projectName ..."
        $startedByThisRun = $true
        Invoke-Compose -Arguments @('up', '-d', '--build')

        Write-Host '正在等待本地应用就绪...'
        $healthDeadline = (Get-Date).AddSeconds(60)
        while ((Get-Date) -lt $healthDeadline) {
            if (Test-LocalHealth) {
                break
            }
            Start-Sleep -Seconds 2
        }
        if (-not (Test-LocalHealth)) {
            throw "应用在 60 秒内未通过本地健康检查：$localHealthUrl"
        }

        Write-Host '应用已就绪，正在等待 Cloudflare Quick Tunnel 地址...'
        $tunnelDeadline = (Get-Date).AddSeconds(60)
        $publicUrl = $null
        while ((Get-Date) -lt $tunnelDeadline) {
            $publicUrl = Get-PublicUrl
            if ($publicUrl) {
                break
            }
            Start-Sleep -Seconds 2
        }
        if (-not $publicUrl) {
            throw 'Cloudflare Quick Tunnel 在 60 秒内未返回公网地址。'
        }

        Write-Host ''
        Write-Host "公网地址：$publicUrl" -ForegroundColor Green
        Write-Host '警告：该地址没有身份认证。持有地址者拥有完整的实验创建、运行和删除权限。' -ForegroundColor Red
        Write-Host '请仅私下分享，并在测试结束后立即运行 scripts/public-test.ps1 Stop。' -ForegroundColor Red
    }
    catch {
        Write-Host "错误：$($_.Exception.Message)" -ForegroundColor Red
        Show-RelevantLogs
        if ($startedByThisRun) {
            Write-Host ''
            Write-Host '启动失败，正在删除本次临时项目和专用数据卷...' -ForegroundColor Yellow
            Remove-PublicTestProject
        }
        exit 1
    }
}

function Show-PublicTestStatus {
    Assert-DockerReady

    Write-Host "Compose 项目：$projectName"
    Invoke-Compose -Arguments @('ps', '-a')

    if (Test-LocalHealth) {
        Write-Host "本地 REST：正常（$localHealthUrl）" -ForegroundColor Green
    }
    else {
        Write-Host "本地 REST：不可用（$localHealthUrl）" -ForegroundColor Yellow
    }

    $publicUrl = Get-PublicUrl
    if ($publicUrl) {
        Write-Host "公网地址：$publicUrl" -ForegroundColor Green
    }
    else {
        Write-Host '公网地址：尚未从 Tunnel 日志中发现' -ForegroundColor Yellow
    }
}

function Show-PublicTestLogs {
    Assert-DockerReady
    Invoke-Compose -Arguments @('logs', '--follow', 'app', 'tunnel')
}

function Stop-PublicTest {
    Assert-DockerReady

    Write-Host "将停止并删除 Compose 项目：$projectName" -ForegroundColor Yellow
    Write-Host '将删除专用临时卷：public-test-data（不会触碰现有 threebody-data 卷）' -ForegroundColor Yellow

    if (-not $Force) {
        $confirmation = Read-Host '输入 YES 确认停止并删除临时数据'
        if ($confirmation -cne 'YES') {
            Write-Host '操作已取消。'
            return
        }
    }

    Invoke-Compose -Arguments @('down', '-v', '--remove-orphans')
    Write-Host '公网测试项目及其专用临时卷已删除。' -ForegroundColor Green
}

if (-not (Test-Path -LiteralPath $composeFile -PathType Leaf)) {
    throw "未找到 Compose 文件：$composeFile"
}

try {
    switch ($Action) {
        'Start' { Start-PublicTest }
        'Status' { Show-PublicTestStatus }
        'Logs' { Show-PublicTestLogs }
        'Stop' { Stop-PublicTest }
    }
}
catch {
    Write-Host "错误：$($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
