package io.acionyx.tunguska.app

enum class ImportCaptureSource {
    MANUAL_TEXT,
    CAMERA_QR,
    IMAGE_QR,
}

data class ImportPreviewState(
    val source: ImportCaptureSource,
    val normalizedSourceSummary: String,
    val sourceScheme: String,
    val profileName: String,
    val endpointSummary: String,
    val profileHash: String,
    val warnings: List<String> = emptyList(),
)

fun ImportCaptureSource.summary(): String = when (this) {
    ImportCaptureSource.MANUAL_TEXT -> "manual share link"
    ImportCaptureSource.CAMERA_QR -> "camera QR scan"
    ImportCaptureSource.IMAGE_QR -> "image QR scan"
}
