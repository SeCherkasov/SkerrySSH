package app.skerry.ui.ai

import androidx.compose.runtime.Composable
import app.skerry.shared.ai.CommandRiskReason
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.risk_bulk_delete
import app.skerry.ui.generated.resources.risk_deletes_files
import app.skerry.ui.generated.resources.risk_disk_device_write
import app.skerry.ui.generated.resources.risk_download_to_shell
import app.skerry.ui.generated.resources.risk_elevated
import app.skerry.ui.generated.resources.risk_firewall_flush
import app.skerry.ui.generated.resources.risk_fork_bomb
import app.skerry.ui.generated.resources.risk_git_destructive
import app.skerry.ui.generated.resources.risk_kills_processes
import app.skerry.ui.generated.resources.risk_mirror_delete
import app.skerry.ui.generated.resources.risk_pipe_to_interpreter
import app.skerry.ui.generated.resources.risk_power_off
import app.skerry.ui.generated.resources.risk_recursive_broad_delete
import app.skerry.ui.generated.resources.risk_recursive_force_delete
import app.skerry.ui.generated.resources.risk_recursive_permissions
import app.skerry.ui.generated.resources.risk_security_file_overwrite
import app.skerry.ui.generated.resources.risk_stops_service
import app.skerry.ui.generated.resources.risk_truncates_content
import app.skerry.ui.generated.resources.risk_uninstalls_packages
import app.skerry.ui.generated.resources.risk_world_writable
import org.jetbrains.compose.resources.stringResource

/**
 * Localized text for a [CommandRiskReason]. The classifier lives in `shared` and only names the
 * reason; the sentence the user reads is a resource, so it follows the app language in the AI bar
 * and in the production-guard confirmation alike.
 */
@Composable
fun commandRiskReasonText(reason: CommandRiskReason): String = stringResource(
    when (reason) {
        CommandRiskReason.ForkBomb -> Res.string.risk_fork_bomb
        CommandRiskReason.RecursiveForceDelete -> Res.string.risk_recursive_force_delete
        CommandRiskReason.RecursiveBroadDelete -> Res.string.risk_recursive_broad_delete
        CommandRiskReason.DiskDeviceWrite -> Res.string.risk_disk_device_write
        CommandRiskReason.DownloadToShell -> Res.string.risk_download_to_shell
        CommandRiskReason.PipeToInterpreter -> Res.string.risk_pipe_to_interpreter
        CommandRiskReason.MirrorDelete -> Res.string.risk_mirror_delete
        CommandRiskReason.PowerOff -> Res.string.risk_power_off
        CommandRiskReason.RecursivePermissions -> Res.string.risk_recursive_permissions
        CommandRiskReason.SecurityFileOverwrite -> Res.string.risk_security_file_overwrite
        CommandRiskReason.FirewallFlush -> Res.string.risk_firewall_flush
        CommandRiskReason.DeletesFiles -> Res.string.risk_deletes_files
        CommandRiskReason.KillsProcesses -> Res.string.risk_kills_processes
        CommandRiskReason.UninstallsPackages -> Res.string.risk_uninstalls_packages
        CommandRiskReason.GitDestructive -> Res.string.risk_git_destructive
        CommandRiskReason.WorldWritable -> Res.string.risk_world_writable
        CommandRiskReason.StopsService -> Res.string.risk_stops_service
        CommandRiskReason.BulkDelete -> Res.string.risk_bulk_delete
        CommandRiskReason.TruncatesContent -> Res.string.risk_truncates_content
        CommandRiskReason.Elevated -> Res.string.risk_elevated
    },
)
