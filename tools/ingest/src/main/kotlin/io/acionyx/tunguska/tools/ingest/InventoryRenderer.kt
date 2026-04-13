package io.acionyx.tunguska.tools.ingest

object InventoryRenderer {
    fun toCsv(items: List<ClassifiedIssue>): String = buildString {
        appendLine("number,title,state,type,impact,categories,url")
        items.forEach { item ->
            val type = if (item.isPullRequest) "pr" else "issue"
            val categories = item.categories.joinToString(separator = "|")
            appendLine(
                listOf(
                    item.number.toString(),
                    escape(item.title),
                    item.state,
                    type,
                    item.impact.name,
                    escape(categories),
                    item.htmlUrl,
                ).joinToString(separator = ","),
            )
        }
    }

    fun toMarkdown(items: List<ClassifiedIssue>): String = buildString {
        appendLine("# Upstream Inventory")
        appendLine()
        appendLine("| Item | State | Impact | Categories | URL |")
        appendLine("|---|---|---|---|---|")
        items.forEach { item ->
            val itemType = if (item.isPullRequest) "PR" else "Issue"
            val categories = if (item.categories.isEmpty()) "-" else item.categories.joinToString("<br>")
            appendLine("| $itemType #${item.number} | ${item.state} | ${item.impact} | $categories | ${item.htmlUrl} |")
        }
    }

    private fun escape(value: String): String = "\"${value.replace("\"", "\"\"")}\""
}

