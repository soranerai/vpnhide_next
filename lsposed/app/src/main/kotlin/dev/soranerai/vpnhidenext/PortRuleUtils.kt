package dev.soranerai.vpnhidenext

internal enum class RuleViolationType {
    DUPLICATE,
    REDUNDANT,
    NONE,
}

internal data class RuleViolation(
    val type: RuleViolationType,
    val coveringRule: PortRule? = null,
)

/**
 * Validates a rule against a set of existing rules.
 */
internal fun validateRule(
    newRule: PortRule,
    existingRules: List<PortRule>,
): RuleViolation {
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
