param(
    [string]$InputPath = (Join-Path $PSScriptRoot '..\entrega_final\Relatorio_Final_GuiaMed_Joao_Roberto.md'),
    [string]$HtmlPath = (Join-Path $PSScriptRoot '..\entrega_final\Relatorio_Final_GuiaMed_Joao_Roberto.html'),
    [string]$PdfPath = (Join-Path $PSScriptRoot '..\entrega_final\Relatorio_Final_GuiaMed_Joao_Roberto.pdf')
)

$ErrorActionPreference = 'Stop'

function Format-Inline([string]$Text) {
    $encoded = [System.Net.WebUtility]::HtmlEncode($Text)
    $encoded = [regex]::Replace($encoded, '\*\*(.+?)\*\*', '<strong>$1</strong>')
    $encoded = [regex]::Replace($encoded, '`([^`]+)`', '<code>$1</code>')
    $encoded = [regex]::Replace(
        $encoded,
        '(https://github\.com/[A-Za-z0-9_.\-/]+)',
        '<a href="$1">$1</a>'
    )
    return $encoded
}

$lines = Get-Content -LiteralPath $InputPath -Encoding UTF8
$body = [System.Text.StringBuilder]::new()
$paragraph = [System.Collections.Generic.List[string]]::new()
$listType = $null

function Close-Paragraph {
    if ($paragraph.Count -gt 0) {
        [void]$body.AppendLine("<p>$(Format-Inline ($paragraph -join ' '))</p>")
        $paragraph.Clear()
    }
}

function Close-List {
    if ($null -ne $listType) {
        [void]$body.AppendLine("</$listType>")
        $script:listType = $null
    }
}

for ($index = 0; $index -lt $lines.Count; $index++) {
    $line = $lines[$index]

    if ($line -match '^\|.*\|\s*$' -and
        $index + 1 -lt $lines.Count -and
        $lines[$index + 1] -match '^\|[\s|:\-]+\|\s*$') {
        Close-Paragraph
        Close-List
        $headers = $line.Trim('|').Split('|') | ForEach-Object { $_.Trim() }
        $index += 2
        [void]$body.AppendLine('<table><thead><tr>')
        foreach ($header in $headers) {
            [void]$body.AppendLine("<th>$(Format-Inline $header)</th>")
        }
        [void]$body.AppendLine('</tr></thead><tbody>')
        while ($index -lt $lines.Count -and $lines[$index] -match '^\|.*\|\s*$') {
            $cells = $lines[$index].Trim('|').Split('|') | ForEach-Object { $_.Trim() }
            [void]$body.AppendLine('<tr>')
            foreach ($cell in $cells) {
                [void]$body.AppendLine("<td>$(Format-Inline $cell)</td>")
            }
            [void]$body.AppendLine('</tr>')
            $index++
        }
        [void]$body.AppendLine('</tbody></table>')
        $index--
        continue
    }

    if ([string]::IsNullOrWhiteSpace($line)) {
        Close-Paragraph
        Close-List
        continue
    }

    if ($line -match '^(#{1,4})\s+(.+)$') {
        Close-Paragraph
        Close-List
        $level = $Matches[1].Length
        [void]$body.AppendLine("<h$level>$(Format-Inline $Matches[2])</h$level>")
        continue
    }

    if ($line -match '^[-*]\s+(.+)$') {
        Close-Paragraph
        if ($listType -ne 'ul') {
            Close-List
            $script:listType = 'ul'
            [void]$body.AppendLine('<ul>')
        }
        [void]$body.AppendLine("<li>$(Format-Inline $Matches[1])</li>")
        continue
    }

    if ($line -match '^\d+\.\s+(.+)$') {
        Close-Paragraph
        if ($listType -ne 'ol') {
            Close-List
            $script:listType = 'ol'
            [void]$body.AppendLine('<ol>')
        }
        [void]$body.AppendLine("<li>$(Format-Inline $Matches[1])</li>")
        continue
    }

    if ($line -match '^>\s*(.+)$') {
        Close-Paragraph
        Close-List
        [void]$body.AppendLine("<blockquote>$(Format-Inline $Matches[1])</blockquote>")
        continue
    }

    $paragraph.Add($line.Trim())
}

Close-Paragraph
Close-List

$html = @"
<!doctype html>
<html lang="pt-BR">
<head>
<meta charset="utf-8">
<title>Relatorio Final GuiaMed - Joao Roberto</title>
<style>
@page { size: A4; margin: 16mm 15mm 18mm; }
* { box-sizing: border-box; }
body { font-family: Arial, Helvetica, sans-serif; color: #172033; font-size: 10.5pt; line-height: 1.42; margin: 0; }
h1 { color: #123f68; font-size: 23pt; border-bottom: 4px solid #ffd600; padding-bottom: 8px; margin: 0 0 18px; }
h2 { color: #123f68; font-size: 16pt; margin: 24px 0 10px; page-break-after: avoid; }
h3 { color: #245f8f; font-size: 12.5pt; margin: 17px 0 7px; page-break-after: avoid; }
h4 { color: #245f8f; font-size: 11pt; margin: 14px 0 6px; page-break-after: avoid; }
p { margin: 0 0 9px; text-align: justify; }
ul, ol { margin: 5px 0 11px 20px; padding: 0; }
li { margin-bottom: 4px; }
table { border-collapse: collapse; width: 100%; margin: 8px 0 14px; font-size: 9pt; page-break-inside: avoid; }
th { background: #123f68; color: white; text-align: left; padding: 6px; border: 1px solid #6885a0; }
td { padding: 6px; border: 1px solid #aebccb; vertical-align: top; }
tr:nth-child(even) td { background: #f3f6f9; }
code { font-family: Consolas, monospace; font-size: 0.92em; background: #eef2f5; padding: 1px 3px; }
blockquote { border-left: 4px solid #ffd600; background: #fff9d9; padding: 8px 12px; margin: 8px 0 12px; }
a { color: #145b8f; text-decoration: none; }
strong { color: #0d2f4e; }
h2:nth-of-type(n+2) { break-before: auto; }
</style>
</head>
<body>
$body
</body>
</html>
"@

$resolvedHtml = [System.IO.Path]::GetFullPath($HtmlPath)
[System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($resolvedHtml)) | Out-Null
[System.IO.File]::WriteAllText($resolvedHtml, $html, [System.Text.UTF8Encoding]::new($false))

$edgeCandidates = @(
    'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe',
    'C:\Program Files\Microsoft\Edge\Application\msedge.exe'
)
$edge = $edgeCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $edge) {
    throw 'Microsoft Edge não encontrado; o HTML foi gerado, mas o PDF não.'
}

$resolvedPdf = [System.IO.Path]::GetFullPath($PdfPath)
$htmlUri = [System.Uri]::new($resolvedHtml).AbsoluteUri
$profile = Join-Path ([System.IO.Path]::GetTempPath()) ("guiamed-pdf-" + [guid]::NewGuid())
& $edge --headless --disable-gpu --no-pdf-header-footer "--user-data-dir=$profile" "--print-to-pdf=$resolvedPdf" $htmlUri | Out-Null
if (-not (Test-Path -LiteralPath $resolvedPdf)) {
    throw 'O Edge não gerou o PDF esperado.'
}

Get-Item -LiteralPath $resolvedHtml, $resolvedPdf | Select-Object FullName, Length, LastWriteTime
