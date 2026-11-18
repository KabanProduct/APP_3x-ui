package dev.threex.mobile.domain

enum class PanelPresence { PRESENT, ABSENT, UNKNOWN }

data class ServerInspection(
    val panel: PanelPresence,
    val checksComplete: Boolean,
    val supportedOs: Boolean,
    val privilegedAccess: Boolean,
    val conflicts: List<String>,
    val existingWorkloads: List<String>,
)

sealed interface SetupDecision {
    data object ConnectExisting : SetupDecision
    data object OfferInstallation : SetupDecision
    data class ReviewRequired(val reasons: List<String>) : SetupDecision
}

fun ServerInspection.decide(): SetupDecision {
    // A stopped or inaccessible panel is still an existing installation.
    if (panel == PanelPresence.PRESENT) return SetupDecision.ConnectExisting
    val reasons = buildList {
        if (panel == PanelPresence.UNKNOWN) add("Не удалось определить наличие 3x-ui")
        if (!checksComplete) add("Проверка сервера не завершена")
        if (!supportedOs) add("Операционная система не поддерживается установщиком")
        if (!privilegedAccess) add("Нет необходимых прав для установки")
        addAll(conflicts)
        if (existingWorkloads.isNotEmpty()) add("На сервере обнаружены другие службы")
    }
    return if (reasons.isEmpty()) SetupDecision.OfferInstallation
    else SetupDecision.ReviewRequired(reasons)
}
