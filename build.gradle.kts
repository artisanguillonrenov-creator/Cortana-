plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

// Tests read files Gradle does not see as inputs (release history, signed manifest, the Git tree,
// tools): they always run, so a reported result is never a stale one. The last run of 2.0.0-rc1
// was reported green from test tasks Gradle had skipped as up to date (D-20260928-066).
subprojects {
    tasks.withType<Test>().configureEach {
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }
}

// Supply chain (phase 32, D-20260928-062): a CycloneDX SBOM and a license inventory of what ships
// (the app's release runtime classpath and the worker's runtime classpath). Hashes come from the
// Gradle dependency verification metadata, licenses from each component's POM (or its parents').
tasks.register("cortanaSbom") {
    group = "verification"
    description = "SBOM CycloneDX 1.5 et inventaire des licences (build/sbom)."
    notCompatibleWithConfigurationCache("walks resolved dependency graphs")
    val outDir = layout.buildDirectory.dir("sbom")
    outputs.dir(outDir)
    // The inputs (resolved graphs, app version) are not declared: always regenerate (cheap) rather than
    // report a stale SBOM as up to date (a 1.2.0 SBOM survived the 2.0.0-rc1 version bump).
    outputs.upToDateWhen { false }
    doLast {
        val verification = rootProject.file("gradle/verification-metadata.xml")
        val hashes = HashMap<String, MutableList<String>>()
        run {
            val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(verification)
            val comps = doc.getElementsByTagName("component")
            for (i in 0 until comps.length) {
                val c = comps.item(i) as org.w3c.dom.Element
                val key = "${c.getAttribute("group")}:${c.getAttribute("name")}:${c.getAttribute("version")}"
                val arts = c.getElementsByTagName("artifact")
                for (j in 0 until arts.length) {
                    val a = arts.item(j) as org.w3c.dom.Element
                    if (a.getAttribute("name").endsWith(".pom") || a.getAttribute("name").endsWith(".module")) continue
                    val sha = (a.getElementsByTagName("sha256").item(0) as? org.w3c.dom.Element)?.getAttribute("value") ?: continue
                    hashes.getOrPut(key) { mutableListOf() } += sha
                }
            }
        }
        // POMs are read from Gradle's module cache (the same files the verification metadata checks).
        val cache = File(gradle.gradleUserHomeDir, "caches/modules-2/files-2.1")
        fun pom(g: String, n: String, v: String): org.w3c.dom.Document? = runCatching {
            val f = File(cache, "$g/$n/$v").walkTopDown().firstOrNull { it.name == "$n-$v.pom" } ?: return@runCatching null
            javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        }.getOrNull()
        fun text(e: org.w3c.dom.Element?, tag: String): String? = e?.getElementsByTagName(tag)?.let { l -> (0 until l.length).map { l.item(it) }.firstOrNull { it.parentNode == e }?.textContent?.trim() }
        // Upstream licenses of components whose published POM declares none (each one documented).
        val declared = mapOf("com.github.adaptech-cz.Tesseract4Android:tesseract4android" to listOf("Apache-2.0 (licence du dépôt amont ; POM JitPack sans licence, D-20260927-036)"))
        fun licenses(g: String, n: String, v: String, depth: Int = 0): List<String> {
            declared["$g:$n"]?.let { return it }
            val d = pom(g, n, v) ?: return emptyList()
            val ls = d.getElementsByTagName("license")
            val names = (0 until ls.length).mapNotNull { text(ls.item(it) as org.w3c.dom.Element, "name") ?: text(ls.item(it) as org.w3c.dom.Element, "url") }
            if (names.isNotEmpty() || depth >= 4) return names.distinct()
            val parent = d.getElementsByTagName("parent").item(0) as? org.w3c.dom.Element ?: return emptyList()
            return licenses(text(parent, "groupId") ?: return emptyList(), text(parent, "artifactId") ?: return emptyList(), text(parent, "version") ?: return emptyList(), depth + 1)
        }
        val targets = listOf(
            Triple("cortana-app", ":app", "releaseRuntimeClasspath"),
            Triple("cortana-worker", ":worker", "runtimeClasspath"),
        )
        val inventory = java.util.TreeMap<String, Pair<String, List<String>>>()
        val out = outDir.get().asFile.apply { mkdirs() }
        val epoch = System.getenv("SOURCE_DATE_EPOCH")?.toLongOrNull()?.times(1000) ?: System.currentTimeMillis()
        val timestamp = java.time.Instant.ofEpochMilli(epoch).toString()
        for ((bomName, path, conf) in targets) {
            val p = project(path)
            val graph = p.configurations.getByName(conf).incoming.resolutionResult.allComponents
                .mapNotNull { it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier }
                .sortedWith(compareBy({ it.group }, { it.module }, { it.version }))
            val components = graph.map { id ->
                val key = "${id.group}:${id.module}:${id.version}"
                val lic = licenses(id.group, id.module, id.version)
                inventory[key] = id.version to lic
                linkedMapOf<String, Any>(
                    "type" to "library", "group" to id.group, "name" to id.module, "version" to id.version,
                    "purl" to "pkg:maven/${id.group}/${id.module}@${id.version}",
                    "hashes" to hashes[key].orEmpty().distinct().map { mapOf("alg" to "SHA-256", "content" to it) },
                    "licenses" to lic.map { mapOf("license" to mapOf("name" to it)) },
                )
            }
            val version = if (path == ":app") (project(":app").extensions.getByName("android") as com.android.build.api.dsl.ApplicationExtension).defaultConfig.versionName ?: "?" else "1"
            val body = linkedMapOf(
                "bomFormat" to "CycloneDX", "specVersion" to "1.5",
                "serialNumber" to "urn:uuid:" + java.util.UUID.nameUUIDFromBytes("$bomName:$version:${components.joinToString { it["purl"].toString() }}".toByteArray()),
                "version" to 1,
                "metadata" to mapOf("timestamp" to timestamp, "component" to mapOf("type" to "application", "name" to bomName, "version" to version,
                    "purl" to "pkg:generic/io.github.artisanguillonrenov/$bomName@$version")),
                "components" to components,
            )
            File(out, "$bomName.cdx.json").writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(body)) + "\n")
        }
        File(out, "LICENSES.md").writeText(buildString {
            appendLine("# Inventaire des licences des dépendances livrées")
            appendLine()
            appendLine("Généré par `./gradlew cortanaSbom` à partir des POM Maven (application : classpath d'exécution release ; worker : classpath d'exécution).")
            appendLine()
            appendLine("| Composant | Version | Licence(s) |")
            appendLine("|---|---|---|")
            inventory.forEach { (k, v) -> appendLine("| ${k.substringBeforeLast(':')} | ${v.first} | ${v.second.joinToString(" ; ").ifEmpty { "non déclarée dans le POM" }} |") }
            appendLine()
            appendLine("${inventory.size} composants ; sans licence déclarée : ${inventory.count { it.value.second.isEmpty() }}.")
            appendLine()
            appendLine("## Ressources embarquées hors Maven (design « Cortana Workspace »)")
            appendLine()
            appendLine("| Ressource | Emplacement | Licence |")
            appendLine("|---|---|---|")
            appendLine("| Geist, Geist Mono (instances statiques 400–700) | `app/src/main/res/font/geist_*.ttf` | SIL Open Font License 1.1 (`assets/licenses/OFL-Geist.txt`, `OFL-GeistMono.txt`) |")
            appendLine("| Sora (instance 600) | `app/src/main/res/font/sora_semibold.ttf` | SIL Open Font License 1.1 (`assets/licenses/OFL-Sora.txt`) |")
            appendLine("| Material Symbols Rounded (graisse 300) | `app/src/main/res/drawable/ms_*.xml` | Apache License 2.0 |")
        })
        println("SBOM : ${out.path} (${inventory.size} composants)")
    }
}
