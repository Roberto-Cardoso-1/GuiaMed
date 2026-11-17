param(
    [string]$PsqlPath = "C:\Program Files\PostgreSQL\18\bin\psql.exe",
    [string]$DatabaseUrl = $env:GUIAMED_EVIDENCE_DATABASE_URL
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $projectRoot "backend\.env"
$evidenceDir = Join-Path $projectRoot "evidencias\seguranca"
New-Item -ItemType Directory -Force $evidenceDir | Out-Null

if (-not (Test-Path -LiteralPath $PsqlPath)) {
    throw "psql não encontrado em $PsqlPath"
}
if (-not $DatabaseUrl) {
    $databaseLine = Get-Content -LiteralPath $envPath |
        Where-Object { $_ -match '^DATABASE_URL=' } |
        Select-Object -First 1
    if (-not $databaseLine) {
        throw "DATABASE_URL ausente em backend/.env"
    }
    $DatabaseUrl = $databaseLine.Substring("DATABASE_URL=".Length)
}

function Invoke-PsqlFile {
    param(
        [string]$SqlFile,
        [string]$OutputFile,
        [switch]$AllowExpectedErrors
    )
    $output = & $PsqlPath $DatabaseUrl -X -f (Join-Path $projectRoot $SqlFile) 2>&1
    $exitCode = $LASTEXITCODE
    $output | Set-Content -LiteralPath (Join-Path $evidenceDir $OutputFile) -Encoding UTF8
    if ($exitCode -ne 0 -and -not $AllowExpectedErrors) {
        throw "Falha em $SqlFile. Consulte $OutputFile"
    }
    return $output
}

Invoke-PsqlFile "db\postgres\01_security.sql" "objetos_seguranca.txt" | Out-Null
$volume = Invoke-PsqlFile "db\postgres\02_seed_volume.sql" "volume.txt"
$permissions = Invoke-PsqlFile "db\postgres\03_evidencias_integracao.sql" "permissoes_integracao.txt" -AllowExpectedErrors
$plans = Invoke-PsqlFile "db\postgres\04_planos_execucao.sql" "planos_execucao.txt"

$checks = [ordered]@{
    volume_50000 = [bool]($volume -match '50000')
    auditoria_1000000 = [bool]($volume -match '1000000')
    acesso_direto_negado = [bool]($permissions -match 'permission denied')
    funcao_autorizada = [bool]($permissions -match 'alerta_alergia')
    mascara_exibida = [bool]($permissions -match '\*{8,}')
    rls_testada = [bool]($permissions -match 'alergias_visiveis_para_b')
    q1_antes_depois = [bool](($plans -match 'Q1 ANTES') -and ($plans -match 'Q1 DEPOIS'))
    q2_antes_depois = [bool](($plans -match 'Q2 ANTES') -and ($plans -match 'Q2 DEPOIS'))
}
$checks | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidenceDir "resumo_validacao.json") -Encoding UTF8

if ($checks.Values -contains $false) {
    throw "Uma ou mais evidências não foram encontradas. Consulte resumo_validacao.json"
}

"Evidências de segurança geradas com sucesso."
$checks.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }
