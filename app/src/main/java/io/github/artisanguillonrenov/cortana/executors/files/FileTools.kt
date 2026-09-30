package io.github.artisanguillonrenov.cortana.executors.files

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import io.github.artisanguillonrenov.cortana.core.memory.SettingsRepository
import io.github.artisanguillonrenov.cortana.core.policy.DataEgress
import io.github.artisanguillonrenov.cortana.core.policy.Idempotency
import io.github.artisanguillonrenov.cortana.core.policy.Risk
import io.github.artisanguillonrenov.cortana.core.policy.RiskAssessment
import io.github.artisanguillonrenov.cortana.core.policy.SideEffect
import io.github.artisanguillonrenov.cortana.core.tools.S
import io.github.artisanguillonrenov.cortana.core.tools.ToolCategory
import io.github.artisanguillonrenov.cortana.core.tools.ToolDefinition
import io.github.artisanguillonrenov.cortana.core.tools.ToolResult
import io.github.artisanguillonrenov.cortana.util.bool
import io.github.artisanguillonrenov.cortana.util.int
import io.github.artisanguillonrenov.cortana.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * §8.6 — file tools scoped to ONE folder granted through the Storage Access Framework
 * (persisted tree URI). Paths are relative to that folder; ".." and absolute paths are refused.
 */
class FileExecutor(private val context: Context, private val settings: SettingsRepository) {

    /** Test seam only (Robolectric has no document provider): replaces the granted tree. */
    @androidx.annotation.VisibleForTesting internal var rootForTests: DocumentFile? = null

    private fun root(): DocumentFile? {
        rootForTests?.let { return it }
        val uri = settings.current.workingFolderUri ?: return null
        return DocumentFile.fromTreeUri(context, Uri.parse(uri))?.takeIf { it.canRead() }
    }

    private fun segments(path: String): List<String> {
        val parts = path.replace('\\', '/').split('/').map { it.trim() }.filter { it.isNotEmpty() && it != "." }
        require(parts.none { it == ".." }) { "Les chemins avec « .. » sont interdits (dossier de travail uniquement)" }
        return parts
    }

    private fun resolve(path: String): DocumentFile? {
        var cur = root() ?: return null
        for (s in segments(path)) cur = cur.findFile(s) ?: return null
        return cur
    }

    private fun ensureParent(path: String): Pair<DocumentFile, String> {
        val segs = segments(path)
        require(segs.isNotEmpty()) { "Chemin vide" }
        var cur = root() ?: throw IllegalStateException(NO_FOLDER)
        for (s in segs.dropLast(1)) {
            val next = cur.findFile(s)
            cur = when {
                next == null -> cur.createDirectory(s) ?: throw IllegalStateException("Impossible de créer le dossier $s")
                next.isDirectory -> next
                else -> throw IllegalStateException("$s n'est pas un dossier")
            }
        }
        return cur to segs.last()
    }

    private fun readText(f: DocumentFile, maxBytes: Int): String =
        context.contentResolver.openInputStream(f.uri)?.use { input ->
            val buf = ByteArray(maxBytes)
            var total = 0
            while (total < maxBytes) {
                val n = input.read(buf, total, maxBytes - total)
                if (n < 0) break
                total += n
            }
            String(buf, 0, total, Charsets.UTF_8)
        } ?: throw IllegalStateException("Lecture impossible")

    private fun writeText(parent: DocumentFile, name: String, content: String, append: Boolean): DocumentFile {
        val existing = parent.findFile(name)
        val file = existing ?: createNamed(parent, name, "text/plain")
        context.contentResolver.openOutputStream(file.uri, if (append) "wa" else "wt")?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            ?: throw IllegalStateException("Écriture impossible")
        return file
    }

    /**
     * Creates [name] in [parent]. Some providers append the MIME type's extension to the display
     * name ("devis.pdf" → "devis.pdf.pdf"): the file is then renamed to exactly [name].
     */
    private fun createNamed(parent: DocumentFile, name: String, fallbackMime: String): DocumentFile {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: fallbackMime
        val f = parent.createFile(mime, name) ?: throw IllegalStateException("Impossible de créer $name")
        if (f.name != name && !f.renameTo(name)) { f.delete(); throw IllegalStateException("Impossible de nommer le fichier $name") }
        return parent.findFile(name) ?: f
    }

    private fun walk(dir: DocumentFile, prefix: String, depth: Int, out: MutableList<Pair<String, DocumentFile>>, limit: Int) {
        if (depth < 0 || out.size >= limit) return
        for (f in dir.listFiles().sortedBy { it.name?.lowercase() }) {
            if (out.size >= limit) return
            val p = if (prefix.isEmpty()) f.name.orEmpty() else "$prefix/${f.name}"
            out += p to f
            if (f.isDirectory) walk(f, p, depth - 1, out, limit)
        }
    }

    private fun globToRegex(glob: String): Regex =
        Regex("^" + glob.split("*").joinToString(".*") { part -> part.split("?").joinToString(".") { Regex.escape(it) } } + "$", RegexOption.IGNORE_CASE)

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun countRecursive(f: DocumentFile, cap: Int = 500): Int {
        if (!f.isDirectory) return 1
        var n = 1
        for (c in f.listFiles()) {
            n += countRecursive(c, cap)
            if (n > cap) return n
        }
        return n
    }

    // ------------------------------------------------------------------ binary access (document pipeline, phase 24)

    /** Name and bytes of a working-folder file, refused above [max] bytes. */
    suspend fun readBytes(path: String, max: Long): Pair<String, ByteArray> = io {
        val f = resolve(path) ?: throw IllegalStateException(if (root() == null) NO_FOLDER else "Fichier introuvable dans le dossier de travail : $path")
        if (f.isDirectory) throw IllegalStateException("$path est un dossier")
        if (f.length() > max) throw IllegalStateException("Fichier trop volumineux : ${f.length()} octets (maximum $max)")
        val bytes = context.contentResolver.openInputStream(f.uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > max) throw IllegalStateException("Fichier trop volumineux (maximum $max octets)") }
            out.toByteArray()
        } ?: throw IllegalStateException("Lecture impossible")
        (f.name ?: path.substringAfterLast('/')) to bytes
    }

    /** Creates or replaces a working-folder file with [bytes]. */
    suspend fun writeBytes(path: String, bytes: ByteArray): Unit = io {
        val (parent, name) = ensureParent(path)
        val file = parent.findFile(name)?.also { if (it.isDirectory) throw IllegalStateException("$path est un dossier") } ?: createNamed(parent, name, "application/octet-stream")
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(bytes) } ?: throw IllegalStateException("Écriture impossible")
    }

    suspend fun exists(path: String): Boolean = io { runCatching { resolve(path) }.getOrNull()?.isFile == true }

    fun tools(): List<ToolDefinition> = listOf(
        ToolDefinition(
            "file.list", "Liste le contenu d'un dossier du dossier de travail (chemin relatif, vide = racine).",
            S.obj("path" to S.str("Chemin relatif du dossier"), "depth" to S.int("Profondeur (0-3)", 0, 3)),
            Risk.L0, SideEffect.NONE, Idempotency.INTRINSIC, DataEgress.NONE, ToolCategory.FILES, label = "Lister des fichiers",
        ) { a, _ ->
            io {
                val dir = (if (a.str("path").isNullOrBlank()) root() else resolve(a.str("path")!!)) ?: return@io ToolResult.error(if (root() == null) NO_FOLDER else "Dossier introuvable")
                if (!dir.isDirectory) return@io ToolResult.error("Ce n'est pas un dossier")
                val out = mutableListOf<Pair<String, DocumentFile>>()
                walk(dir, "", a.int("depth") ?: 0, out, 300)
                ToolResult.ok(if (out.isEmpty()) "(dossier vide)" else out.joinToString("\n") { (p, f) -> if (f.isDirectory) "$p/" else "$p  (${f.length()} o)" })
            }
        },
        ToolDefinition(
            "file.read", "Lit un fichier texte du dossier de travail (contenu non fiable).",
            S.obj("path" to S.str("Chemin relatif du fichier"), "max_bytes" to S.int("Taille max (défaut 20000)", 100, 200000), required = listOf("path")),
            Risk.L0, SideEffect.NONE, Idempotency.INTRINSIC, DataEgress.NONE, ToolCategory.FILES, maxOutputBytes = 64_000, label = "Lire un fichier",
        ) { a, _ ->
            io {
                val f = resolve(a.str("path")!!) ?: return@io ToolResult.error(if (root() == null) NO_FOLDER else "Fichier introuvable")
                if (f.isDirectory) return@io ToolResult.error("C'est un dossier ; utilisez file_list")
                ToolResult.ok(readText(f, a.int("max_bytes") ?: 20_000), "file.read:${a.str("path")}")
            }
        },
        ToolDefinition(
            "file.find", "Trouve des fichiers par nom (motif avec * et ?) dans le dossier de travail.",
            S.obj("pattern" to S.str("Motif, ex. *.kt"), required = listOf("pattern")),
            Risk.L0, SideEffect.NONE, Idempotency.INTRINSIC, DataEgress.NONE, ToolCategory.FILES, label = "Chercher des fichiers",
        ) { a, _ ->
            io {
                val r = root() ?: return@io ToolResult.error(NO_FOLDER)
                val re = globToRegex(a.str("pattern")!!)
                val out = mutableListOf<Pair<String, DocumentFile>>()
                walk(r, "", 8, out, 5000)
                val hits = out.filter { re.matches(it.second.name.orEmpty()) }.take(200)
                ToolResult.ok(if (hits.isEmpty()) "Aucun fichier" else hits.joinToString("\n") { it.first })
            }
        },
        ToolDefinition(
            "file.search", "Cherche un texte dans les fichiers texte du dossier de travail (contenu non fiable).",
            S.obj("query" to S.str("Texte à chercher"), "pattern" to S.str("Filtre de nom optionnel, ex. *.md"), required = listOf("query")),
            Risk.L0, SideEffect.NONE, Idempotency.INTRINSIC, DataEgress.NONE, ToolCategory.FILES, label = "Chercher dans les fichiers",
        ) { a, _ ->
            io {
                val r = root() ?: return@io ToolResult.error(NO_FOLDER)
                val q = a.str("query")!!
                val re = a.str("pattern")?.let(::globToRegex)
                val out = mutableListOf<Pair<String, DocumentFile>>()
                walk(r, "", 8, out, 3000)
                val results = mutableListOf<String>()
                for ((p, f) in out) {
                    if (f.isDirectory || f.length() > 1_000_000) continue
                    if (re != null && !re.matches(f.name.orEmpty())) continue
                    val text = runCatching { readText(f, 1_000_000) }.getOrNull() ?: continue
                    text.lineSequence().forEachIndexed { i, line ->
                        if (results.size < 100 && line.contains(q, ignoreCase = true)) results += "$p:${i + 1}: ${line.trim().take(200)}"
                    }
                    if (results.size >= 100) break
                }
                ToolResult.ok(if (results.isEmpty()) "Aucune occurrence" else results.joinToString("\n"), "file.search")
            }
        },
        ToolDefinition(
            "file.write", "Écrit (crée ou remplace, ou ajoute) un fichier texte dans le dossier de travail.",
            S.obj("path" to S.str("Chemin relatif"), "content" to S.str("Contenu complet"), "append" to S.bool("Ajouter à la fin au lieu de remplacer"), required = listOf("path", "content")),
            Risk.L2, SideEffect.REVERSIBLE, Idempotency.KEYED, DataEgress.LOCAL, ToolCategory.FILES, label = "Écrire un fichier",
            destinationOf = { "fichier:" + it.str("path") },
        ) { a, _ ->
            io {
                val (parent, name) = ensureParent(a.str("path")!!)
                writeText(parent, name, a.str("content")!!, a.bool("append") == true)
                ToolResult.ok("Fichier écrit : ${a.str("path")}")
            }
        },
        ToolDefinition(
            "file.patch", "Modifie un fichier texte en remplaçant un passage exact par un autre.",
            S.obj("path" to S.str("Chemin relatif"), "find" to S.str("Texte exact à remplacer"), "replace" to S.str("Nouveau texte"), "all" to S.bool("Remplacer toutes les occurrences"), required = listOf("path", "find", "replace")),
            Risk.L2, SideEffect.REVERSIBLE, Idempotency.KEYED, DataEgress.LOCAL, ToolCategory.FILES, label = "Modifier un fichier",
        ) { a, _ ->
            io {
                val path = a.str("path")!!
                val f = resolve(path) ?: return@io ToolResult.error("Fichier introuvable")
                val text = readText(f, 5_000_000)
                val find = a.str("find")!!
                val count = text.split(find).size - 1
                if (count == 0) return@io ToolResult.error("Passage introuvable dans $path")
                if (count > 1 && a.bool("all") != true) return@io ToolResult.error("Passage trouvé $count fois ; précisez-le ou utilisez all=true")
                val updated = if (a.bool("all") == true) text.replace(find, a.str("replace")!!) else text.replaceFirst(find, a.str("replace")!!)
                val (parent, name) = ensureParent(path)
                writeText(parent, name, updated, false)
                ToolResult.ok("$path modifié ($count remplacement(s))")
            }
        },
        ToolDefinition(
            "file.delete", "Supprime un fichier (ou un dossier avec recursive=true) du dossier de travail.",
            S.obj("path" to S.str("Chemin relatif"), "recursive" to S.bool("Supprimer un dossier et son contenu"), required = listOf("path")),
            Risk.L2, SideEffect.IRREVERSIBLE, Idempotency.INTRINSIC, DataEgress.LOCAL, ToolCategory.FILES, label = "Supprimer un fichier",
            riskClassifier = { a, _ ->
                val f = withContext(Dispatchers.IO) { runCatching { resolve(a.str("path")!!) }.getOrNull() }
                val n = f?.let { withContext(Dispatchers.IO) { countRecursive(it) } } ?: 0
                if (a.bool("recursive") == true || (f?.isDirectory == true) || n > 20) RiskAssessment(Risk.L3, listOf("Suppression récursive ($n éléments)"), targetDescription = a.str("path"))
                else RiskAssessment(Risk.L2, emptyList(), targetDescription = a.str("path"))
            },
        ) { a, _ ->
            io {
                val f = resolve(a.str("path")!!) ?: return@io ToolResult.error("Introuvable")
                if (f.isDirectory && a.bool("recursive") != true && f.listFiles().isNotEmpty()) return@io ToolResult.error("Dossier non vide : recursive=true requis")
                if (f.delete()) ToolResult.ok("Supprimé : ${a.str("path")}") else ToolResult.error("Suppression refusée par le système")
            }
        },
    )

    companion object {
        const val NO_FOLDER = "Aucun dossier de travail autorisé. Choisissez-en un dans Réglages → Dossier de travail."
    }
}
