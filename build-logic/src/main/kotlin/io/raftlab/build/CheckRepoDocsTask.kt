package io.raftlab.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import java.nio.file.Path

abstract class CheckRepoDocsTask : DefaultTask() {
    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    @get:Input
    abstract val requiredFiles: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val markdownFiles: ConfigurableFileCollection

    @TaskAction
    fun check() {
        val root: Path = rootDirectory.get().asFile.toPath().toAbsolutePath().normalize()
        val problems = mutableListOf<String>()
        val required = requiredFiles.get()
        for (path in required) {
            if (!Files.isRegularFile(root.resolve(path))) {
                problems.add("missing required file: $path")
            }
        }
        val fenceStart = Regex("^ {0,3}(```|~~~)")
        val inlineCode = Regex("`[^`]*`")
        val inlineLink = Regex("""\]\(\s*<?([^()\s<>]+)>?(?:\s+"[^"]*")?\s*\)""")
        val refDefinition = Regex("""^ {0,3}\[[^\]]+\]:\s*<?(\S+?)>?(?:\s.*)?$""")
        val files = markdownFiles.files.map { it.toPath().toAbsolutePath().normalize() }.sortedBy { relative(root, it) }
        var links = 0
        for (markdown in files) {
            val rel = relative(root, markdown)
            var inFence = false
            var inComment = false
            markdown.toFile().readLines(Charsets.UTF_8).forEachIndexed { index, original ->
                val lineNo = index + 1
                if (inFence) {
                    if (fenceStart.containsMatchIn(original)) inFence = false
                    return@forEachIndexed
                }
                if (fenceStart.containsMatchIn(original)) {
                    inFence = true
                    return@forEachIndexed
                }
                val visible = StringBuilder()
                var rest = original
                while (true) {
                    if (inComment) {
                        val end = rest.indexOf("-->")
                        if (end < 0) break
                        rest = rest.substring(end + 3)
                        inComment = false
                    } else {
                        val start = rest.indexOf("<!--")
                        if (start < 0) {
                            visible.append(rest)
                            break
                        }
                        visible.append(rest, 0, start)
                        rest = rest.substring(start + 4)
                        inComment = true
                    }
                }
                val text = visible.toString().replace(inlineCode, "")
                val targets = inlineLink.findAll(text).map { it.groupValues[1] }.toMutableList()
                refDefinition.find(text)?.let { targets.add(it.groupValues[1]) }
                for (target in targets) {
                    if (target.contains("://") || target.startsWith("mailto:") || target.startsWith("#")) continue
                    val path = target.substringBefore('#').substringBefore('?').replace("%20", " ")
                    if (path.isEmpty()) continue
                    val base = if (path.startsWith("/")) root else markdown.parent
                    val resolved = base.resolve(path.removePrefix("/")).normalize()
                    when {
                        !resolved.startsWith(root) -> problems.add("$rel:$lineNo: link escapes repository '$target'")
                        !Files.exists(resolved) -> problems.add("$rel:$lineNo: broken link '$target'")
                        else -> links++
                    }
                }
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException("Repository docs check failed:\n" + problems.joinToString("\n") { "  $it" })
        }
        logger.lifecycle(
            "checkRepoDocs: ${required.size} required files present, ${files.size} Markdown files, $links relative links resolved",
        )
    }

    private fun relative(root: Path, path: Path): String = root.relativize(path).joinToString("/")
}
