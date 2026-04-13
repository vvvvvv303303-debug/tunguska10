package io.acionyx.tunguska.tools.ingest

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val json: Json = Json {
    ignoreUnknownKeys = true
}

fun main(args: Array<String>) {
    val parsed = Args.parse(args.toList())
    if (parsed == null) {
        printUsage()
        return
    }

    val input = Files.readString(parsed.input).removePrefix("\uFEFF")
    val items = json.decodeFromString(ListSerializer(GitHubIssueLike.serializer()), input)
    val classified = IssueClassifier().classify(items)

    Files.createDirectories(parsed.csv.parent)
    Files.createDirectories(parsed.markdown.parent)
    Files.writeString(parsed.csv, InventoryRenderer.toCsv(classified))
    Files.writeString(parsed.markdown, InventoryRenderer.toMarkdown(classified))
}

private data class Args(
    val input: Path,
    val csv: Path,
    val markdown: Path,
) {
    companion object {
        fun parse(args: List<String>): Args? {
            if (args.size != 6) return null
            val values = args.chunked(2).associate { (key, value) -> key to value }
            val input = values["--input"] ?: return null
            val csv = values["--csv"] ?: return null
            val markdown = values["--markdown"] ?: return null
            return Args(
                input = Path.of(input),
                csv = Path.of(csv),
                markdown = Path.of(markdown),
            )
        }
    }
}

private fun printUsage() {
    println("Usage: ingest --input <issues.json> --csv <out.csv> --markdown <out.md>")
}
