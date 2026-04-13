package io.acionyx.tunguska.app

sealed interface QrImportPayloadSelection {
    data class Single(
        val payload: String,
    ) : QrImportPayloadSelection

    data class Failure(
        val message: String,
    ) : QrImportPayloadSelection
}

object QrImportPayloadSelector {
    fun select(rawValues: List<String?>): QrImportPayloadSelection {
        val normalized = rawValues
            .mapNotNull { value -> value?.trim()?.takeIf(String::isNotBlank) }
            .distinct()

        return when {
            normalized.isEmpty() ->
                QrImportPayloadSelection.Failure("QR import failed: the code does not contain a text share link.")

            normalized.size > 1 ->
                QrImportPayloadSelection.Failure(
                    "QR import failed: multiple text payloads were found. Select one code manually instead of guessing.",
                )

            else -> QrImportPayloadSelection.Single(normalized.single())
        }
    }
}
