package io.acionyx.tunguska.storage

import java.nio.file.Path

internal fun Path.existsCompat(): Boolean = toFile().exists()

internal fun Path.readUtf8Text(): String = toFile()
    .inputStream()
    .bufferedReader(Charsets.UTF_8)
    .use { it.readText() }

internal fun Path.writeUtf8Text(payload: String) {
    toFile()
        .outputStream()
        .bufferedWriter(Charsets.UTF_8)
        .use { writer -> writer.write(payload) }
}

internal fun Path.createDirectoriesCompat() {
    val directory = toFile()
    if (!directory.exists() && !directory.mkdirs()) {
        error("Unable to create directory '${directory.absolutePath}'.")
    }
}

internal fun Path.deleteIfExistsCompat(): Boolean {
    val target = toFile()
    return !target.exists() || target.delete()
}

internal fun Path.deleteCompat() {
    check(toFile().delete()) {
        "Unable to delete '${toAbsolutePath().normalize()}'."
    }
}
