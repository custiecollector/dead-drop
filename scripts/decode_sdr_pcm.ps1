param(
    [Parameter(ValueFromRemainingArguments=$true)]
    [string[]]$PcmCommand
)

<#
Feed demodulated SDR/audio PCM into DeadDrop's desktop decoder.

The command after -- must write mono signed 16-bit little-endian PCM to stdout.
DeadDrop currently expects 44.1 kHz input; resample before piping if your
demodulator produces another rate.

Example shape:
  powershell -File scripts\decode_sdr_pcm.ps1 -- your_sdr_fm_demodulator.exe -f <freq> -r 44100 -

Set DEADDROP_DESKTOP_JAR to override the JAR path; default is build\deaddrop-desktop.jar.
Set DEADDROP_VAULT_PASSPHRASE and the decoder will open the local desktop vault for encrypted group packets.
#>

if (-not $PcmCommand -or $PcmCommand.Count -eq 0) {
    Write-Error "Usage: powershell -File scripts\decode_sdr_pcm.ps1 -- <pcm-producing command> [args...]"
    exit 2
}
if ($PcmCommand[0] -eq "--") {
    $PcmCommand = $PcmCommand[1..($PcmCommand.Count - 1)]
}
if (-not $PcmCommand -or $PcmCommand.Count -eq 0) {
    Write-Error "Missing PCM-producing command."
    exit 2
}

$jar = if ($env:DEADDROP_DESKTOP_JAR) { $env:DEADDROP_DESKTOP_JAR } else { "build\deaddrop-desktop.jar" }
if (-not (Test-Path $jar)) {
    Write-Error "DeadDrop desktop JAR not found: $jar. Run scripts\build_desktop.sh or set DEADDROP_DESKTOP_JAR."
    exit 1
}

$vaultArgs = @()
if ($env:DEADDROP_VAULT_PASSPHRASE) {
    $vaultArgs = @("--vault-passphrase-env", "DEADDROP_VAULT_PASSPHRASE")
}

$exe = $PcmCommand[0]
$cmdArgs = if ($PcmCommand.Count -gt 1) { $PcmCommand[1..($PcmCommand.Count - 1)] } else { @() }
& $exe @cmdArgs | java -jar $jar --decode-pcm-stdin --rate 44100 --format s16le --channels 1 @vaultArgs
exit $LASTEXITCODE
