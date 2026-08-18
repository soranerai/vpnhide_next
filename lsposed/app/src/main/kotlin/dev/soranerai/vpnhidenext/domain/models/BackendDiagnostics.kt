package dev.soranerai.vpnhidenext.domain.models

/** A component status is deliberately independent from installation metadata. */
enum class DiagnosticStatus {
    AVAILABLE,
    MISSING,
    INACTIVE,
    BROKEN,
    BLOCKED,
    UNKNOWN,
}

enum class BackendKind {
    KMOD,
    BUILT_IN,
    UNKNOWN,
}

data class ComponentDiagnostic(
    val status: DiagnosticStatus,
    val detail: String? = null,
)

data class BackendDiagnostics(
    val root: ComponentDiagnostic,
    val backend: ComponentDiagnostic,
    val backendKind: BackendKind,
    val bridge: ComponentDiagnostic,
    val lsposed: ComponentDiagnostic,
)

/** Raw facts collected in one privileged probe. No single fact proves activity. */
data class BackendProbeFacts(
    val root: Boolean,
    val controlDevice: Boolean,
    val controlTool: Boolean,
    val controlToolResponding: Boolean,
    val bridgeKmod: Boolean,
    val bridgeKpatch: Boolean,
    val bridgeValid: Boolean,
    val bridgeDisabled: Boolean,
    val loadedKmod: Boolean,
    val lsposedHooksActive: Boolean,
)

/**
 * Converts independent probe facts into user-facing states. In particular,
 * module.prop/bridge presence can only affect [bridge], never [backend].
 */
object BackendDiagnosticsEvaluator {
    fun evaluate(facts: BackendProbeFacts): BackendDiagnostics {
        val root =
            ComponentDiagnostic(
                if (facts.root) DiagnosticStatus.AVAILABLE else DiagnosticStatus.MISSING,
            )
        if (!facts.root) {
            val blocked = ComponentDiagnostic(DiagnosticStatus.BLOCKED)
            return BackendDiagnostics(root, blocked, BackendKind.UNKNOWN, blocked, blocked)
        }

        val bridgeInstalled = facts.bridgeKmod || facts.bridgeKpatch
        val bridge =
            when {
                !bridgeInstalled -> ComponentDiagnostic(DiagnosticStatus.MISSING)
                facts.bridgeDisabled -> ComponentDiagnostic(DiagnosticStatus.INACTIVE)
                !facts.bridgeValid -> ComponentDiagnostic(DiagnosticStatus.BROKEN)
                else -> ComponentDiagnostic(DiagnosticStatus.AVAILABLE)
            }

        // /dev/vpnhide_ctrl is the runtime contract. The CLI is only a
        // management client and may be unavailable while the backend is
        // already active.
        val backendActive = facts.controlDevice
        val backend =
            when {
                !facts.controlDevice && !facts.controlTool -> ComponentDiagnostic(DiagnosticStatus.MISSING)
                backendActive -> ComponentDiagnostic(DiagnosticStatus.AVAILABLE)
                else -> ComponentDiagnostic(DiagnosticStatus.INACTIVE)
            }
        val kind =
            when {
                !backendActive -> BackendKind.UNKNOWN
                facts.loadedKmod -> BackendKind.KMOD
                else -> BackendKind.BUILT_IN
            }

        val lsposed =
            when {
                !backendActive -> ComponentDiagnostic(DiagnosticStatus.BLOCKED)
                facts.lsposedHooksActive -> ComponentDiagnostic(DiagnosticStatus.AVAILABLE)
                else -> ComponentDiagnostic(DiagnosticStatus.INACTIVE)
            }
        return BackendDiagnostics(root, backend, kind, bridge, lsposed)
    }
}
