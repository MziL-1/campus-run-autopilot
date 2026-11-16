<#
.SYNOPSIS
    Black-box probe of the live 运动世界校园 (ZjWh) server using the upstream
    7.3.92 protocol suite, to find out which parts of the protocol still work
    against the current 7.4.x app version.

.DESCRIPTION
    One stage per CLI invocation. Each stage records exit code, the upstream
    CLI output and the HTTP status of every request it made, into
    reference/out/<stage>.json (git-ignored).

    Credentials are read from environment variables only and are scrubbed from
    everything this script writes.

.EXAMPLE
    $env:CAMPUS_PHONE = '<phone>'; $env:CAMPUS_PASSWORD = '<password>'
    tools/probe.ps1

.EXAMPLE
    # Re-run one stage with a token captured earlier (from the login stage JSON).
    $env:CAMPUS_TOKEN = '<token>'; $env:CAMPUS_UID = '<uid>'; $env:CAMPUS_UNID = '<unid>'
    tools/probe.ps1 -Stage rules
#>
[CmdletBinding()]
param(
    [ValidateSet('all', 'sync-time', 'endpoints', 'login', 'profile', 'rules', 'history', 'fixture')]
    [string]$Stage = 'all'
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# --------------------------------------------------------------------------
# Paths
# --------------------------------------------------------------------------
$RepoRoot   = Split-Path -Parent $PSScriptRoot
$Suite      = Join-Path $RepoRoot 'reference/upstream/协议/7.3.92'
$Python     = Join-Path $RepoRoot 'reference/venv/Scripts/python.exe'
$NodeModules = Join-Path $RepoRoot 'reference/node/node_modules'
$OutDir     = Join-Path $RepoRoot 'reference/out'
$ObsDir     = Join-Path $OutDir '_obs'
$FixtureRaw = Join-Path $ObsDir 'fixture-raw'
$FixtureDir = Join-Path $RepoRoot 'core/src/test/resources/policy'

if (-not (Test-Path $Python)) {
    throw "上游套件运行环境缺失: $Python (先执行 brief Step 1)"
}

# --------------------------------------------------------------------------
# Credential hygiene: env only, never argv, never files
# --------------------------------------------------------------------------
$Phone    = $env:CAMPUS_PHONE
$Password = $env:CAMPUS_PASSWORD
$Token    = $env:CAMPUS_TOKEN
$Uid      = $env:CAMPUS_UID
$Unid     = $env:CAMPUS_UNID

# Every value here must never reach reference/out/*.json or the fixture files.
$script:Secrets = @($Phone, $Password, $Token) | Where-Object { $_ }

function Protect-Output {
    <# Scrubs credentials, bare phone numbers, tokens and encrypted envelopes. #>
    param([string]$Text)
    if ([string]::IsNullOrEmpty($Text)) { return $Text }

    # JSON-shaped secrets first, so the key stays readable and only the value goes.
    $Text = [regex]::Replace($Text, '"token"\s*:\s*"[^"]*"', '"token":"[REDACTED]"')
    $Text = [regex]::Replace($Text, '"phone"\s*:\s*"[^"]*"', '"phone":"[REDACTED]"')
    $Text = [regex]::Replace($Text, '"mobile"\s*:\s*"[^"]*"', '"mobile":"[REDACTED]"')

    if ($script:Secrets) {
        foreach ($secret in $script:Secrets) {
            $Text = $Text.Replace($secret, '[REDACTED]')
        }
    }
    $Text = [regex]::Replace($Text, '(?<!\d)1[3-9]\d{9}(?!\d)', '[REDACTED]')      # any bare phone
    $Text = [regex]::Replace($Text, 'token\s*[=:]\s*"?[A-Za-z0-9._\-]{8,}', 'token=[REDACTED]', 'IgnoreCase')

    # NetSec envelope: keep the fact that a response was encrypted, drop the blob.
    $Text = [regex]::Replace($Text, '"r"\s*:\s*"[^"]{20,}"', '"r":"[encrypted]"')
    return $Text
}

function ConvertTo-SafeArgument {
    <# Masks credential values when they would otherwise appear in recorded argv. #>
    param([string]$Argument)
    if ($script:Secrets -and ($script:Secrets -contains $Argument)) { return '[REDACTED]' }
    return Protect-Output $Argument
}

# --------------------------------------------------------------------------
# HTTP observer
#     Injected through PYTHONPATH as sitecustomize.py so the upstream suite is
#     used completely unmodified. It records method/URL/status of every
#     urllib request; request headers and bodies are never touched.
# --------------------------------------------------------------------------
$observerSource = @'
# -*- coding: utf-8 -*-
"""Auto-imported at interpreter start (PYTHONPATH hook) by tools/probe.ps1.

Records method, URL and HTTP status of every urllib request the upstream
protocol suite makes, as JSONL at $PROBE_HTTP_LOG. Deliberately does not
record request headers or request bodies: those carry credentials.
"""
import json
import os
import urllib.error
import urllib.request

_LOG_PATH = os.environ.get("PROBE_HTTP_LOG")
_ORIGINAL_URLOPEN = urllib.request.urlopen


def _append(entry):
    try:
        with open(_LOG_PATH, "a", encoding="utf-8") as handle:
            handle.write(json.dumps(entry, ensure_ascii=False) + "\n")
    except Exception:
        pass


class _RecordingResponse:
    def __init__(self, response, entry):
        self._response = response
        self._entry = entry

    def read(self, *args, **kwargs):
        data = self._response.read(*args, **kwargs)
        self._entry["responseBytes"] = len(data)
        self._entry["responsePreview"] = data.decode("utf-8", "replace")[:600]
        _append(self._entry)
        return data

    def __enter__(self):
        return self

    def __exit__(self, *exc_info):
        try:
            self._response.close()
        except Exception:
            pass
        return False

    def __getattr__(self, name):
        return getattr(self._response, name)


def _recording_urlopen(request, *args, **kwargs):
    url = getattr(request, "full_url", None) or str(request)
    method = request.get_method() if hasattr(request, "get_method") else "GET"
    entry = {"method": method, "url": url, "status": None}
    try:
        response = _ORIGINAL_URLOPEN(request, *args, **kwargs)
    except urllib.error.HTTPError as exc:
        entry["status"] = exc.code
        try:
            entry["responsePreview"] = exc.read().decode("utf-8", "replace")[:600]
        except Exception:
            pass
        _append(entry)
        raise
    except Exception as exc:
        entry["error"] = "%s: %s" % (type(exc).__name__, exc)
        _append(entry)
        raise
    entry["status"] = getattr(response, "status", None) or getattr(response, "code", None)
    return _RecordingResponse(response, entry)


if _LOG_PATH:
    urllib.request.urlopen = _recording_urlopen
'@

$fixtureSource = @'
# -*- coding: utf-8 -*-
"""Dumps decrypted runModePolicy / geo-fence responses for parser fixtures.

Run by tools/probe.ps1 with the upstream suite on sys.path. Credentials come
from the environment only; tools/probe.ps1 scrubs the output before it is
copied into core/src/test/resources/policy/.
"""
import json
import os
import sys

sys.path.insert(0, os.environ["PROBE_SUITE"])

from policy.client import PolicyClient
from policy.config import URL_GEO_FENCE

out_dir = os.environ["PROBE_FIXTURE_RAW"]
os.makedirs(out_dir, exist_ok=True)

client = PolicyClient(
    uid=os.environ.get("CAMPUS_UID"),
    token=os.environ.get("CAMPUS_TOKEN"),
    unid=os.environ.get("CAMPUS_UNID"),
)


def dump(name, payload):
    path = os.path.join(out_dir, name)
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2)
    print("WROTE %s" % name)


rule = client.get_run_policy(run_mode=1)
dump("run_mode_policy.json", rule.raw if rule is not None else {})

dump("geo_fence.json", client.execute_request("POST", URL_GEO_FENCE, body_plain={"geoFenceUpdateTime": 0}))

print("FIXTURE_DUMP_DONE")
'@

$endpointSource = @'
# -*- coding: utf-8 -*-
"""Endpoint reachability + signature-layer probe for the 5 stages' endpoints.

Two passes over the same routes, both through the upstream transport (same
crypto, same headers), so the only variable is the credential state:
  * anonymous - no uid/token headers at all;
  * dummy     - uid/token headers present but not a real session.
If the dummy pass gets a business answer ("session invalid") instead of a
header/decrypt rejection, the NetSecKit header signature is still accepted.
Written by tools/probe.ps1.
"""
import json
import os
import sys

sys.path.insert(0, os.environ["PROBE_SUITE"])

import history.config as history_config
import policy.config as policy_config
import profile.config as profile_config
from core.client import BaseNetSecClient
from core.config import HOST_DISCOVERY

CASES = [
    ("profile.user_info", "GET", profile_config.URL_USER_INFO, None),
    ("profile.home_page_base_user_info", "GET", profile_config.URL_HOME_BASE_USER + "?uid=0", None),
    ("policy.run_mode_policy", "POST", policy_config.URL_RUN_POLICY,
     {"runMode": 1, "ruleUpdateTime": 0, "geoFenceUpdateTime": 0, "selectUnid": 0, "operateType": 0}),
    ("policy.geo_fence_for_run", "POST", policy_config.URL_GEO_FENCE, {"geoFenceUpdateTime": 0}),
    ("policy.personal_semester_info", "POST", policy_config.URL_SEMESTER_INFO, {"runMode": 1}),
    ("history.runnings_records", "POST", history_config.URL_RUN_RECORDS,
     {"pageNum": 1, "pageSize": 5, "sid": 0, "type": 1}),
    ("history.calendar_list", "POST", HOST_DISCOVERY + "/api/v70100/run/calendar/list",
     {"year": 2026, "month": 11}),
]

PASSES = [
    ("anonymous", BaseNetSecClient()),
    ("dummy-credentials", BaseNetSecClient(uid="1", token="0" * 32)),
]

for pass_name, client in PASSES:
    for name, method, url, body in CASES:
        answer = client.execute_request(method, url, body_plain=body)
        if isinstance(answer, dict):
            code = answer.get("error")
            message = answer.get("message", "")
        else:
            code = "non-json"
            message = str(answer)[:120]
        print("ENDPOINT[%s] %s code=%s message=%s"
              % (pass_name, name, code, json.dumps(message, ensure_ascii=False)))
'@

New-Item -ItemType Directory -Force -Path $ObsDir | Out-Null
[System.IO.File]::WriteAllText(
    (Join-Path $ObsDir 'probe_endpoints.py'),
    $endpointSource,
    (New-Object System.Text.UTF8Encoding($false)))
[System.IO.File]::WriteAllText(
    (Join-Path $ObsDir 'sitecustomize.py'),
    $observerSource,
    (New-Object System.Text.UTF8Encoding($false)))
[System.IO.File]::WriteAllText(
    (Join-Path $ObsDir 'dump_policy_fixture.py'),
    $fixtureSource,
    (New-Object System.Text.UTF8Encoding($false)))

# --------------------------------------------------------------------------
# Stage runner
# --------------------------------------------------------------------------
function Invoke-Stage {
    param(
        [string]$Name,
        [string]$Script,                       # relative to $Suite
        [string[]]$CliArgs = @(),
        [string]$SuccessPattern,
        [hashtable]$ExtraEnv = @{}
    )

    $httpLog = Join-Path $ObsDir "$Name.http.jsonl"
    Remove-Item $httpLog -Force -ErrorAction SilentlyContinue

    $environment = @{
        PROBE_HTTP_LOG   = $httpLog
        PYTHONPATH       = $ObsDir
        PYTHONIOENCODING = 'utf-8'
        NODE_PATH        = $NodeModules
        PROBE_SUITE      = $Suite
        PROBE_FIXTURE_RAW = $FixtureRaw
    }
    foreach ($key in $ExtraEnv.Keys) { $environment[$key] = $ExtraEnv[$key] }

    $saved = @{}
    foreach ($key in $environment.Keys) {
        $saved[$key] = [Environment]::GetEnvironmentVariable($key)
        [Environment]::SetEnvironmentVariable($key, $environment[$key])
    }

    Write-Host "[stage] $Name -> $Script"
    $startedAt = Get-Date
    $rawOutput = ''
    $exitCode = -1
    # Native stderr must be captured as data, not turned into a terminating error.
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    Push-Location $Suite
    try {
        $rawOutput = & $Python $Script @CliArgs 2>&1 | Out-String
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorAction
        Pop-Location
        foreach ($key in $saved.Keys) {
            [Environment]::SetEnvironmentVariable($key, $saved[$key])
        }
    }

    $summary = Protect-Output $rawOutput
    $outputLines = @($summary -split "`r?`n" | Where-Object { $_.Trim() })
    $verdict = if ($SuccessPattern -and ($summary -match $SuccessPattern)) { 'pass' } else { 'fail' }
    $failures = @($outputLines | Where-Object { $_ -match '失败|错误|Traceback' })

    $requests = @()
    if (Test-Path $httpLog) {
        foreach ($line in (Get-Content $httpLog -Encoding UTF8)) {
            if (-not $line.Trim()) { continue }
            $entry = $line | ConvertFrom-Json
            $requests += [pscustomobject]@{
                method          = $entry.method
                url             = $entry.url
                status          = $entry.status
                transportError  = $entry.error
                responseBytes   = $entry.responseBytes
                responsePreview = Protect-Output $entry.responsePreview
            }
        }
    }

    $recordedArgs = @($Script) + @($CliArgs | ForEach-Object { ConvertTo-SafeArgument $_ })
    $record = [pscustomobject]@{
        stage        = $Name
        startedAt    = $startedAt.ToString('o')
        durationSec  = [math]::Round(((Get-Date) - $startedAt).TotalSeconds, 1)
        command      = ($recordedArgs -join ' ')
        exitCode     = $exitCode
        verdict      = $verdict
        failureLines = $failures
        output       = $outputLines
        http         = $requests
    }

    $jsonPath = Join-Path $OutDir "$Name.json"
    [System.IO.File]::WriteAllText(
        $jsonPath,
        ($record | ConvertTo-Json -Depth 10),
        (New-Object System.Text.UTF8Encoding($false)))

    Write-Host ("[stage] {0} verdict={1} exit={2} http={3} -> {4}" -f $Name, $verdict, $exitCode, $requests.Count, $jsonPath)
    return [pscustomobject]@{ Record = $record; Raw = $rawOutput; Verdict = $verdict }
}

function Get-TokenContext {
    <# Returns token/uid/unid: from the environment, or from a fresh login. #>
    $context = [pscustomobject]@{ Token = $Token; Uid = $Uid; Unid = $Unid }
    if ($context.Token -and $context.Uid) { return $context }

    if (-not $Phone -or -not $Password) {
        throw '缺少凭据: 需要 $env:CAMPUS_PHONE/$env:CAMPUS_PASSWORD 登录, 或 $env:CAMPUS_TOKEN/$env:CAMPUS_UID 复用会话'
    }

    $login = Invoke-Stage -Name 'login' -Script 'login/cli.py' `
        -CliArgs @('-u', $Phone, '-p', $Password) -SuccessPattern '登录结果: 成功'

    if ($login.Verdict -ne 'pass') {
        throw "登录失败, 后续阶段无法探测 (详见 reference/out/login.json)"
    }

    # Parsed from the live output only, never written back to disk unscrubbed.
    if ($login.Raw -match 'token=(\S+)') { $context.Token = $matches[1] }
    if ($login.Raw -match '\buid=(\d+)') { $context.Uid = $matches[1] }
    if ($login.Raw -match '\bunid=(\d+)') { $context.Unid = $matches[1] }
    return $context
}

function Invoke-ContextStage {
    param(
        [string]$Name,
        [string]$Script,
        [string[]]$CliArgs,
        [string]$SuccessPattern,
        [pscustomobject]$Context
    )
    $arguments = @($CliArgs) + @('-t', $Context.Token, '-u', $Context.Uid)
    if ($Context.Unid) { $arguments += @('--unid', $Context.Unid) }
    return Invoke-Stage -Name $Name -Script $Script -CliArgs $arguments -SuccessPattern $SuccessPattern
}

function Export-Fixture {
    <# Decrypted policy responses -> fixture dir, redacted and provenance-noted. #>
    param([pscustomobject]$Context)

    New-Item -ItemType Directory -Force -Path $FixtureDir | Out-Null
    New-Item -ItemType Directory -Force -Path $FixtureRaw | Out-Null
    Remove-Item (Join-Path $FixtureRaw '*.json') -Force -ErrorAction SilentlyContinue

    if (-not $Context -or -not $Context.Token -or -not $Context.Uid) {
        Write-Warning 'fixture 无法抓取 (无有效会话), 写入占位文件'
        Write-FixturePlaceholder
        return
    }

    $dump = Invoke-Stage -Name 'fixture' -Script (Join-Path $ObsDir 'dump_policy_fixture.py') `
        -SuccessPattern 'FIXTURE_DUMP_DONE' `
        -ExtraEnv @{
            CAMPUS_TOKEN = $Context.Token
            CAMPUS_UID   = $Context.Uid
            CAMPUS_UNID  = $Context.Unid
        }

    if ($dump.Verdict -ne 'pass') {
        Write-Warning 'fixture 抓取失败, 保留占位文件'
        Write-FixturePlaceholder
        return
    }
    foreach ($name in @('run_mode_policy.json', 'geo_fence.json')) {
        $source = Join-Path $FixtureRaw $name
        if (-not (Test-Path $source)) { continue }
        $text = Protect-Output (Get-Content $source -Raw -Encoding UTF8)
        [System.IO.File]::WriteAllText(
            (Join-Path $FixtureDir $name),
            $text,
            (New-Object System.Text.UTF8Encoding($false)))
        Write-Host "[fixture] $name -> $FixtureDir"
    }
}

function Write-FixturePlaceholder {
    <# Keeps the fixture directory committed before a live capture exists. #>
    foreach ($name in @('run_mode_policy.json', 'geo_fence.json')) {
        $placeholder = [ordered]@{
            _placeholder = $true
            _reason      = 'live capture unavailable, see docs/protocol-7.4-findings.md'
            _capturedAt  = (Get-Date).ToString('o')
            _source      = 'tools/probe.ps1 -Stage fixture'
        } | ConvertTo-Json
        [System.IO.File]::WriteAllText(
            (Join-Path $FixtureDir $name),
            $placeholder,
            (New-Object System.Text.UTF8Encoding($false)))
    }
}

# --------------------------------------------------------------------------
# Stages
# --------------------------------------------------------------------------
switch ($Stage) {
    'sync-time' {
        Invoke-Stage -Name 'sync-time' -Script 'profile/cli.py' -SuccessPattern '时钟同步: 服务端时间=' | Out-Null
    }
    'endpoints' {
        Invoke-Stage -Name 'endpoints' -Script (Join-Path $ObsDir 'probe_endpoints.py') `
            -SuccessPattern 'ENDPOINT\[' | Out-Null
    }
    'login' {
        if (-not $Phone -or -not $Password) {
            throw '缺少凭据: 需要 $env:CAMPUS_PHONE/$env:CAMPUS_PASSWORD'
        }
        Invoke-Stage -Name 'login' -Script 'login/cli.py' `
            -CliArgs @('-u', $Phone, '-p', $Password) -SuccessPattern '登录结果: 成功' | Out-Null
    }
    'profile' {
        $context = Get-TokenContext
        Invoke-ContextStage -Name 'profile' -Script 'profile/cli.py' -CliArgs @() `
            -SuccessPattern '详细档案: 班级=' -Context $context | Out-Null
    }
    'rules' {
        $context = Get-TokenContext
        Invoke-ContextStage -Name 'rules' -Script 'policy/cli.py' -CliArgs @('--mode', '1') `
            -SuccessPattern '跑步规则: 模式=' -Context $context | Out-Null
    }
    'history' {
        $context = Get-TokenContext
        Invoke-ContextStage -Name 'history' -Script 'history/cli.py' -CliArgs @() `
            -SuccessPattern '历史记录: 页码=' -Context $context | Out-Null
    }
    'fixture' {
        try { $context = Get-TokenContext }
        catch { Write-Warning $_.Exception.Message; $context = $null }
        Export-Fixture -Context $context
    }
    'all' {
        Invoke-Stage -Name 'sync-time' -Script 'profile/cli.py' -SuccessPattern '时钟同步: 服务端时间=' | Out-Null
        Invoke-Stage -Name 'endpoints' -Script (Join-Path $ObsDir 'probe_endpoints.py') `
            -SuccessPattern 'ENDPOINT\[' | Out-Null
        try {
            $context = Get-TokenContext
        }
        catch {
            Write-Warning $_.Exception.Message
            Write-Warning '已在登录/会话处受阻: sync-time 与 endpoints 阶段仍有效'
            Export-Fixture -Context $null
            return
        }
        Invoke-ContextStage -Name 'profile' -Script 'profile/cli.py' -CliArgs @() `
            -SuccessPattern '详细档案: 班级=' -Context $context | Out-Null
        Invoke-ContextStage -Name 'rules' -Script 'policy/cli.py' -CliArgs @('--mode', '1') `
            -SuccessPattern '跑步规则: 模式=' -Context $context | Out-Null
        Invoke-ContextStage -Name 'history' -Script 'history/cli.py' -CliArgs @() `
            -SuccessPattern '历史记录: 页码=' -Context $context | Out-Null
        Export-Fixture -Context $context
    }
}
