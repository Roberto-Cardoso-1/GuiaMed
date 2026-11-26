$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$project = Split-Path -Parent $PSScriptRoot
$workspace = Split-Path -Parent $project
$pg = 'C:\Program Files\PostgreSQL\18\bin'
$scratch = Join-Path $project ('.demo-perfis-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $scratch | Out-Null
$data = Join-Path $scratch 'db'
$api = $null
$started = $false
try {
    $password = [guid]::NewGuid().ToString('N')
    $pwfile = Join-Path $scratch 'init-password'
    [IO.File]::WriteAllText($pwfile, $password)
    & "$pg\initdb.exe" -D $data -U postgres --auth=scram-sha-256 "--pwfile=$pwfile" | Out-Null
    $launcher = Start-Process -WindowStyle Hidden -PassThru -FilePath "$pg\pg_ctl.exe" `
        -ArgumentList "start -D `"$data`" -l `"$scratch\postgres.log`" -o `"-h 127.0.0.1 -p 55433`" -w" `
        -RedirectStandardOutput "$scratch\pg-start.log" -RedirectStandardError "$scratch\pg-start-error.log"
    $launcher.WaitForExit()
    if ($launcher.ExitCode -ne 0) { throw 'Falha ao iniciar o PostgreSQL isolado.' }
    $started = $true
    $env:PGPASSWORD = $password
    $params = @('-X','-h','127.0.0.1','-p','55433','-d','postgres','-v','ON_ERROR_STOP=1')
    "CREATE ROLE app_api LOGIN PASSWORD '$password';" | & "$pg\psql.exe" @params -U postgres | Out-Null
    & "$pg\psql.exe" @params -U postgres -c 'GRANT CREATE ON SCHEMA public TO app_api' | Out-Null
    foreach ($file in '00_roles_admin.sql','01_security.sql') {
        & "$pg\psql.exe" @params -U postgres -f (Join-Path "$project\db\postgres" $file) | Out-Null
    }
    & "$pg\psql.exe" @params -U app_api -f "$project\db\postgres\05_seed_demo_perfis.sql" | Out-Null
    $env:DATABASE_URL = "postgresql://app_api:${password}@127.0.0.1:55433/postgres"
    $env:GUIAMED_DEMO_TOKEN_A = [guid]::NewGuid().ToString('N')
    $env:GUIAMED_DEMO_TOKEN_B = [guid]::NewGuid().ToString('N')
    $api = Start-Process -WindowStyle Hidden -PassThru -WorkingDirectory "$project\backend" `
        -FilePath "$project\backend\.venv\Scripts\python.exe" `
        -ArgumentList '-m','uvicorn','main:app','--host','127.0.0.1','--port','18081' `
        -RedirectStandardOutput "$scratch\api.log" -RedirectStandardError "$scratch\api-error.log"
    $health = $null
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        try { $health = Invoke-RestMethod http://127.0.0.1:18081/health; break }
        catch { Start-Sleep -Milliseconds 500 }
    }
    if ($health.database -ne 'connected') { throw 'API não confirmou conexão com o banco isolado.' }

    $env:Path = "$workspace\.tools\node24;$env:Path"
    $env:N8N_USER_FOLDER = $scratch
    $env:N8N_DIAGNOSTICS_ENABLED = 'false'
    $env:N8N_VERSION_NOTIFICATIONS_ENABLED = 'false'
    $env:N8N_BLOCK_ENV_ACCESS_IN_NODE = 'false'
    $env:N8N_ENFORCE_SETTINGS_FILE_PERMISSIONS = 'false'
    $n8n = "$workspace\.tools\n8n\node_modules\.bin\n8n.cmd"
    $results = @{}
    foreach ($profile in 'A','B') {
        $flow = Get-Content -Raw "$project\n8n\workflows\guiamed_demo_perfis.json" | ConvertFrom-Json -AsHashtable
        $id = "guiamedDemoPerfil$profile"
        $flow.id = $id
        $http = $flow.nodes[1].parameters
        $http.url = 'http://127.0.0.1:18081/rag/demo/alergia'
        $http.authentication = 'none'
        $http.Remove('genericAuthType')
        $http.sendHeaders = $true
        $expression = '={{ "Bearer " + $env.GUIAMED_DEMO_TOKEN_' + $profile + ' }}'
        $http.headerParameters = @{ parameters = @(@{name='Authorization'; value=$expression}) }
        $inputFile = Join-Path $scratch "$id.json"
        [IO.File]::WriteAllText($inputFile, ($flow | ConvertTo-Json -Depth 25))
        & $n8n import:workflow "--input=$inputFile" | Out-Null
        $output = (& $n8n execute "--id=$id") -join "`n"
        $start = $output.IndexOf('Execution was successful:')
        if ($start -lt 0) { throw "Falha na execução n8n do perfil $profile." }
        $jsonStart = $output.IndexOf('{', $start)
        $jsonEnd = $output.LastIndexOf('}')
        $run = $output.Substring($jsonStart, $jsonEnd - $jsonStart + 1) | ConvertFrom-Json -Depth 70 -AsHashtable
        $result = $run.data.resultData.runData['Consultar alergia fictícia do perfil A'][0].data.main[0][0].json
        $results[$profile] = [ordered]@{ status = $result.statusCode; resposta = $result.body }
    }
    if ($results.A.status -ne 200 -or $results.A.resposta.alergia_registrada -ne $true -or
        $results.B.status -ne 403 -or $results.B.resposta.detail.mensagem -ne 'Acesso negado' -or
        $results.B.resposta.Contains('alergia_registrada')) { throw 'Resultados inesperados na demonstração.' }
    $idA = [guid]$results.A.resposta.auditoria_id
    $idB = [guid]$results.B.resposta.detail.auditoria_id
    $audit = & "$pg\psql.exe" @params -U app_api -P pager=off -c `
        "SET ROLE rl_suporte; SELECT id, requester_masked, operation, sources, requested_at FROM guiamed_api.support_audit_masked WHERE id IN ('$idA', '$idB') ORDER BY requested_at;"
    if (($audit -join ' ') -notmatch 'demo_alergia_permitida' -or
        ($audit -join ' ') -notmatch 'demo_alergia_negada') { throw 'Registros de auditoria incompletos.' }
    $destination = "$project\evidencias\n8n\demo_perfis_execucao.txt"
    @('GuiaMed | Duas execuções reais do workflow n8n com PostgreSQL local',
      'Consulta: existência do mesmo registro fictício de alergia do perfil A',
      '', 'PERFIL A', ($results.A | ConvertTo-Json -Depth 10),
      '', 'PERFIL B', ($results.B | ConvertTo-Json -Depth 10),
      '', 'AUDITORIA POSTGRESQL | visão do perfil de suporte') + $audit |
        Set-Content -LiteralPath $destination -Encoding utf8
    Write-Output $destination
} finally {
    if ($api -and -not $api.HasExited) { Stop-Process -InputObject $api }
    if ($started) { & "$pg\pg_ctl.exe" stop -D $data -m fast -w | Out-Null }
    if ($scratch -like "$project\.demo-perfis-*" -and (Test-Path -LiteralPath $scratch)) {
        Remove-Item -LiteralPath $scratch -Recurse -Force
    }
}
