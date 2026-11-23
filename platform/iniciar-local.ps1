$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$local = Join-Path $root '.local'
$pgBin = Join-Path $local 'postgres/bin'
$pgData = Join-Path $local 'database'
$redisData = Join-Path $local 'cache'
$redisBin = Join-Path $local 'redis'
$java = Join-Path $local 'java/bin/java.exe'
$jar = Join-Path $root 'backend/target/platform-1.0.0.jar'
$dist = Join-Path $root 'frontend/dist'
$accountFile = Join-Path $local 'acesso-inicial.txt'
$config = Join-Path $local 'runtime-config.json'
$appUrl = 'http://127.0.0.1:8080'
$pgPort = 15432
$redisPort = 16379

function Random-Hex {
    param([int]$size)
    [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes($size)).ToLowerInvariant()
}

function Find-Listener {
    param([int]$port)
    Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
}

function Confirm-Owner {
    param($listener, [string]$executable)
    if (!$listener) { return }
    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $($listener.OwningProcess)"
    if ($processInfo.ExecutablePath -ine $executable) { throw "A porta $($listener.LocalPort) está sendo usada por outro serviço." }
}

if (!(Test-Path $jar) -or !(Test-Path (Join-Path $dist 'index.html'))) { throw 'Compile o frontend e o backend antes de iniciar.' }
New-Item -ItemType Directory -Force $local,$redisData | Out-Null
if (!(Test-Path $config)) {
    @{
        databasePassword = Random-Hex 24
        redisPassword = Random-Hex 24
        jwtSecret = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
        adminEmail = 'admin@nexo.local'
        adminPassword = Random-Hex 12
    } | ConvertTo-Json | Set-Content -Encoding utf8 $config
}
$secrets = Get-Content $config -Raw | ConvertFrom-Json

$pgServer = Join-Path $pgBin 'postgres.exe'
$pgControl = Join-Path $pgBin 'pg_ctl.exe'
$pg = Find-Listener $pgPort
Confirm-Owner $pg $pgServer
if (!$pg) {
    if (!(Test-Path (Join-Path $pgData 'PG_VERSION'))) {
        $passwordInput = Join-Path $local 'pg-initial-password.txt'
        try {
            Set-Content -Encoding ascii -LiteralPath $passwordInput -Value $secrets.databasePassword
            & (Join-Path $pgBin 'initdb.exe') -D $pgData -U nexo --auth=scram-sha-256 --encoding=UTF8 --locale=C --pwfile=$passwordInput | Out-Null
            if ($LASTEXITCODE) { throw 'Falha ao inicializar o banco PostgreSQL.' }
        } finally {
            if (Test-Path -LiteralPath $passwordInput) { Remove-Item -LiteralPath $passwordInput }
        }
    }
    $pgLog = Join-Path $local 'postgres.log'
    $boot = Start-Process $pgControl -ArgumentList @('-D', ('"' + $pgData + '"'), '-l', ('"' + $pgLog + '"'), '-o', ('"-h 127.0.0.1 -p ' + $pgPort + '"'), '-w', '-t', '15', 'start') -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $local 'pg-start.log') -RedirectStandardError (Join-Path $local 'pg-start-error.log')
    if (!$boot.WaitForExit(20000) -or $boot.ExitCode -ne 0) { throw 'Falha ao iniciar PostgreSQL. Consulte .local/postgres.log.' }

}

$redisServer = Join-Path $redisBin 'redis-server.exe'
$redisCli = Join-Path $redisBin 'redis-cli.exe'
$redis = Find-Listener $redisPort
Confirm-Owner $redis $redisServer
if (!$redis) {
    $redisConfig = Join-Path $redisData 'redis.conf'
    @(
        'bind 127.0.0.1'
        "port $redisPort"
        'protected-mode yes'
        "requirepass $($secrets.redisPassword)"
        'appendonly yes'
        'dir .'
        'logfile redis.log'
    ) | Set-Content -LiteralPath $redisConfig -Encoding ascii
    Start-Process $redisServer -ArgumentList 'redis.conf' -WorkingDirectory $redisData -WindowStyle Hidden | Out-Null
}
$env:REDISCLI_AUTH = $secrets.redisPassword
try {
    for ($i = 0; $i -lt 25; $i++) {
        $answer = & $redisCli -p $redisPort ping 2>$null
        if ($answer -eq 'PONG') { break }
        Start-Sleep -Milliseconds 250
    }
    if ($answer -ne 'PONG') { throw 'Redis não está respondendo.' }
} finally { Remove-Item Env:REDISCLI_AUTH -ErrorAction SilentlyContinue }

$app = Find-Listener 8080
Confirm-Owner $app $java
if (!$app) {
    $env:PORT = '8080'
    $env:BIND_ADDRESS = '127.0.0.1'
    $env:DATABASE_URL = "jdbc:postgresql://127.0.0.1:${pgPort}/postgres"
    $env:DATABASE_USER = 'nexo'
    $env:DATABASE_PASSWORD = $secrets.databasePassword
    $env:REDIS_URL = "redis://:$($secrets.redisPassword)@127.0.0.1:${redisPort}"
    $env:JWT_SECRET = $secrets.jwtSecret
    $env:PUBLIC_ORIGIN = $appUrl
    $env:COOKIE_SECURE = 'false'
    if (!(Test-Path $accountFile)) {
        $env:ADMIN_EMAIL = $secrets.adminEmail
        $env:ADMIN_PASSWORD = $secrets.adminPassword
    }
    $static = ([uri]($dist + [IO.Path]::DirectorySeparatorChar)).AbsoluteUri
    $process = Start-Process $java -ArgumentList @('-jar', ('"' + $jar + '"'), "--spring.web.resources.static-locations=$static") -WorkingDirectory $root -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $local 'app.log') -RedirectStandardError (Join-Path $local 'app-error.log')
}
for ($i = 0; $i -lt 80; $i++) {
    try {
        $page = Invoke-WebRequest "$appUrl/login" -TimeoutSec 2
        if ($page.Content -match '<title>Morera IPTV</title>') { break }
    } catch {
        if ($process -and $process.HasExited) { throw 'O Spring Boot encerrou. Consulte .local/app.log.' }
    }
    Start-Sleep -Milliseconds 500
}
if (!$page -or $page.Content -notmatch '<title>Morera IPTV</title>') { throw 'O painel não respondeu na porta 8080.' }

if (!(Test-Path $accountFile)) {
    $payload = @{ email=$secrets.adminEmail; password=$secrets.adminPassword } | ConvertTo-Json -Compress
    for ($i = 0; $i -lt 5; $i++) {
        try {
            $profile = Invoke-RestMethod "$appUrl/api/auth/login" -Method Post -ContentType 'application/json' -Headers @{'X-Nexo-Request'='1'} -Body $payload -SessionVariable iptvSession
            break
        } catch { Start-Sleep -Milliseconds 800 }
    }
    if (!$profile -or $profile.role -ne 'ADMIN') { throw 'O painel respondeu, mas o acesso administrativo não foi validado.' }
    $dashboard = Invoke-RestMethod "$appUrl/api/admin/dashboard" -WebSession $iptvSession
    if (!$dashboard.status) { throw 'O dashboard administrativo não respondeu.' }
    Invoke-RestMethod "$appUrl/api/auth/logout" -Method Post -Headers @{'X-Nexo-Request'='1'} -WebSession $iptvSession | Out-Null
    @(
        "Morera IPTV: $appUrl"
        "E-mail: $($secrets.adminEmail)"
        "Senha inicial: $($secrets.adminPassword)"
        'Você pode alterar a senha em Administração > Usuários.'
    ) | Set-Content -Encoding utf8 -LiteralPath $accountFile
    Write-Output 'Login de administrador e dashboard verificados.'
}
Write-Output "Morera IPTV disponível em $appUrl"


