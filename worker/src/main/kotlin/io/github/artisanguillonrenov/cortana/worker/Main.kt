package io.github.artisanguillonrenov.cortana.worker

import java.io.File
import java.net.NetworkInterface

private fun usage() = """
    Cortana worker — exécute les compilations et tests demandés par votre tablette Cortana.

    Utilisation : java -jar cortana-worker.jar <commande> [options]
      serve   [--data DOSSIER] [--port 8765]   démarre le worker (affiche un code d'appairage)
      pair    [--data DOSSIER]                 crée un nouveau code d'appairage (10 minutes)
      status  [--data DOSSIER] [--json]        état : identité, bac à sable, capacités, appareils, tâches, webhooks
      devices [--data DOSSIER] [--json]        liste les appareils appairés
      jobs    [--data DOSSIER] [--json]        liste les tâches (même contrat que l'API : JobStatus)
      hooks   [--data DOSSIER] [--json]        liste les webhooks hébergés (sans leurs secrets)
      revoke  ID [--data DOSSIER]              révoque un appareil immédiatement
      plugin-keygen FICHIER                    crée une clé d'éditeur de plugins (à garder secrète)
      plugin-pack DOSSIER CLÉ SORTIE           signe et empaquette un plugin (DOSSIER contient plugin.json)
    Données : ~/.cortana-worker par défaut (clé TLS, appareils, projets, tâches).
""".trimIndent()

fun main(args: Array<String>) = cli(args, System.out)

/** The command line; [out] receives everything printed (so the administration output can be tested). */
fun cli(args: Array<String>, out: java.io.PrintStream) {
    fun println(x: Any?) = out.println(x)
    val a = args.toMutableList()
    val json = a.remove("--json")
    fun opt(name: String): String? { val i = a.indexOf(name); if (i < 0 || i + 1 >= a.size) return null; val v = a[i + 1]; a.removeAt(i + 1); a.removeAt(i); return v }
    val data = File(opt("--data") ?: (System.getProperty("user.home") + "/.cortana-worker"))
    val port = opt("--port")?.toIntOrNull()
    when (a.firstOrNull()) {
        "serve", null -> {
            val server = WorkerServer(data, port).start()
            println("Cortana worker « ${server.config.name} » (${server.config.workerId}) à l'écoute sur le port ${server.port}.")
            println("Empreinte TLS : ${server.identity.certificateSha256}")
            println("Bac à sable isolé : ${if (server.sandbox.isolatedAvailable) "disponible (espaces de noms Linux)" else "indisponible (mode processus seulement)"}")
            println("Pour appairer la tablette (code valable 10 minutes), dans Cortana → Appareils → Appairer, collez :")
            localAddresses().ifEmpty { listOf("ADRESSE-DE-CE-PC") }.forEach { println("  " + server.pairingString(it)) }
            Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
            Thread.currentThread().join()
        }
        "pair" -> {
            val server = WorkerServer(data, port) // not started: only issues a code the running server will accept
            localAddresses().ifEmpty { listOf("ADRESSE-DE-CE-PC") }.forEach { println(server.pairingString(it)) }
        }
        "status" -> {
            val s = WorkerAdmin(WorkerServer(data, port)).status()
            if (json) println(WorkerAdmin.json(WorkerAdmin.Status.serializer(), s))
            else {
                println("Worker « ${s.name} » (${s.workerId}), protocole ${s.protocol}")
                println("Empreinte TLS : ${s.tlsCertificateSha256}")
                println("Bac à sable isolé : ${if (s.isolatedSandbox) "disponible" else "indisponible"} · modes ${s.capabilities.sandboxModes.joinToString()}")
                println("Appareils : ${s.activeDevices} actif(s) sur ${s.devices} · tâches : ${s.jobs} (${s.runningJobs} en cours) · webhooks : ${s.hooks} (${s.pendingHookEvents} événement(s) en attente)")
            }
        }
        "devices" -> {
            val list = WorkerAdmin(WorkerServer(data, port)).devices()
            if (json) println(WorkerAdmin.jsonList(PairedDevice.serializer(), list))
            else list.forEach { println("${it.deviceId}  ${it.name}  ${if (it.revoked) "RÉVOQUÉ" else "actif"}  vu : ${it.lastSeenAt ?: "jamais"}") }
        }
        "jobs" -> {
            val list = WorkerAdmin(WorkerServer(data, port)).jobs()
            if (json) println(WorkerAdmin.jsonList(WorkerAdmin.JobLine.serializer(), list))
            else list.forEach { println("${it.status.jobId}  ${it.status.status}  ${it.deviceId}  code ${it.status.result?.exitCode ?: "-"}  ${it.status.result?.durationMs?.let { d -> "$d ms" } ?: ""}") }
        }
        "hooks" -> {
            val list = WorkerAdmin(WorkerServer(data, port)).hooks()
            if (json) println(WorkerAdmin.jsonList(HookSummary.serializer(), list))
            else list.forEach { println("${it.name}  ${it.info.path}  ${it.deviceId}  ${it.maxPerMinute}/min  ${it.pendingEvents} en attente") }
        }
        "revoke" -> println(if (a.size > 1 && DeviceStore(data).revoke(a[1])) "Appareil ${a[1]} révoqué." else "Appareil inconnu.")
        "plugin-keygen" -> {
            val f = File(a.getOrNull(1) ?: return println(usage()))
            if (f.exists()) return println("${f.path} existe déjà : refus d'écraser une clé.")
            val k = io.github.artisanguillonrenov.cortana.contracts.PluginFormat.newKeyPair()
            f.privateWrite(java.util.Base64.getEncoder().encodeToString(k.private.encoded) + "\n" + java.util.Base64.getEncoder().encodeToString(k.public.encoded) + "\n")
            println("Clé créée : ${f.path} (empreinte ${io.github.artisanguillonrenov.cortana.contracts.PluginFormat.fingerprint(java.util.Base64.getEncoder().encodeToString(k.public.encoded))})")
        }
        "plugin-pack" -> {
            if (a.size < 4) return println(usage())
            val lines = File(a[2]).readLines()
            val kf = java.security.KeyFactory.getInstance("EC")
            val keys = java.security.KeyPair(kf.generatePublic(java.security.spec.X509EncodedKeySpec(java.util.Base64.getDecoder().decode(lines[1].trim()))),
                kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(java.util.Base64.getDecoder().decode(lines[0].trim()))))
            val bytes = io.github.artisanguillonrenov.cortana.contracts.PluginFormat.pack(File(a[1]), keys)
            val pkg = io.github.artisanguillonrenov.cortana.contracts.PluginFormat.read(bytes) // self-check before writing
            File(a[3]).writeBytes(bytes)
            println("Plugin ${pkg.manifest.id} ${pkg.manifest.version} écrit dans ${a[3]} (clé ${pkg.publisherKeyFingerprint}, sha256 ${pkg.sha256}).")
        }
        else -> println(usage())
    }
}

private fun localAddresses(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { it.inetAddresses.toList() }.filter { it is java.net.Inet4Address && it.isSiteLocalAddress }.map { it.hostAddress }
}.getOrDefault(emptyList())
