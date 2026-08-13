package dev.soranerai.vpnhidenext

internal enum class RuleViolationType {
    INVALID_START,
    INVALID_END,
    START_AFTER_END,
    DUPLICATE,
    REDUNDANT,
    NONE,
}

internal data class RuleViolation(
    val type: RuleViolationType,
    val coveringRule: PortRule? = null,
)

internal const val MIN_PORT = 1
internal const val MAX_PORT = 65535

internal fun validatePortRange(
    startPort: Int?,
    endPort: Int?,
): RuleViolationType =
    when {
        startPort == null || startPort !in MIN_PORT..MAX_PORT -> RuleViolationType.INVALID_START
        endPort == null || endPort !in MIN_PORT..MAX_PORT -> RuleViolationType.INVALID_END
        startPort > endPort -> RuleViolationType.START_AFTER_END
        else -> RuleViolationType.NONE
    }

internal fun isValidPortRange(
    startPort: Int,
    endPort: Int,
): Boolean = validatePortRange(startPort, endPort) == RuleViolationType.NONE

/**
 * Validates a rule against a set of existing rules.
 */
internal fun validateRule(
    newRule: PortRule,
    existingRules: List<PortRule>,
): RuleViolation {
    val rangeViolation = validatePortRange(newRule.startPort, newRule.endPort)
    if (rangeViolation != RuleViolationType.NONE) return RuleViolation(rangeViolation)

    val exactDuplicate =
        existingRules.find { existing ->
            existing.id != newRule.id &&
                existing.enabled &&
                existing.startPort == newRule.startPort &&
                existing.endPort == newRule.endPort &&
                existing.protocol == newRule.protocol
        }
    if (exactDuplicate != null) return RuleViolation(RuleViolationType.DUPLICATE, exactDuplicate)

    val redundant =
        existingRules.find { existing ->
            existing.id != newRule.id &&
                existing.enabled &&
                (existing.protocol == PortProtocol.BOTH || existing.protocol == newRule.protocol) &&
                existing.startPort <= newRule.startPort && existing.endPort >= newRule.endPort
        }
    if (redundant != null) return RuleViolation(RuleViolationType.REDUNDANT, redundant)

    return RuleViolation(RuleViolationType.NONE)
}

/**
 * Checks if two rules overlap.
 */
internal fun isRuleOverlapping(
    rule1: PortRule,
    rule2: PortRule,
): Boolean {
    if (rule1.id == rule2.id) return false
    val protoOverlap = rule1.protocol == PortProtocol.BOTH || rule2.protocol == PortProtocol.BOTH || rule1.protocol == rule2.protocol
    if (!protoOverlap) return false

    return rule1.startPort <= rule2.endPort && rule2.startPort <= rule1.endPort
}
