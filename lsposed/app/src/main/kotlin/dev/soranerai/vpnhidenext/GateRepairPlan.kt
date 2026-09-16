package dev.soranerai.vpnhidenext

import dev.soranerai.vpnhidenext.domain.models.BackendKind
import dev.soranerai.vpnhidenext.domain.models.DiagnosticStatus

/** Repair choices the gate may present for one diagnostic snapshot. */
internal data class GateRepairPlan(
    val offerKmod: Boolean,
    val offerBuiltIn: Boolean,
    val bridgeOnly: Boolean,
)

/**
 * Maps a known compatibility repair to exactly its owning component.
 *
 * Runtime diagnostics may offer alternative installation methods only when
 * compatibility could not identify the installed component pair. A known
 * built-in mismatch must never be presented as a missing kmod.
 */
internal fun resolveGateRepairPlan(
    requiredComponent: String?,
    backendStatus: DiagnosticStatus,
    backendKind: BackendKind,
    bridgeStatus: DiagnosticStatus,
    installedNativeIsKmod: Boolean?,
): GateRepairPlan {
    when (requiredComponent) {
        "kmod" -> return GateRepairPlan(offerKmod = true, offerBuiltIn = false, bridgeOnly = false)
        "bridge" -> return GateRepairPlan(offerKmod = false, offerBuiltIn = true, bridgeOnly = true)
        "built-in" -> return GateRepairPlan(offerKmod = false, offerBuiltIn = true, bridgeOnly = false)
    }

    val bridgeOnly =
        backendKind == BackendKind.BUILT_IN && bridgeStatus != DiagnosticStatus.AVAILABLE
    val backendUnavailable = backendStatus != DiagnosticStatus.AVAILABLE
    return GateRepairPlan(
        offerKmod = backendUnavailable,
        offerBuiltIn = installedNativeIsKmod != true && (backendUnavailable || bridgeOnly),
        bridgeOnly = bridgeOnly,
    )
}
