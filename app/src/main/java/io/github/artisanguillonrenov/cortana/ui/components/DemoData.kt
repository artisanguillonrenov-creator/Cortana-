package io.github.artisanguillonrenov.cortana.ui.components

/**
 * The prototype's demonstration data (design handoff, class `Component`). Used ONLY by previews and
 * visual tests: the app itself always shows the owner's real data.
 */
object DemoData {
    val steps = listOf(
        "Créer le projet Android (Kotlin + Jetpack Compose)", "Développer l’interface et les fonctionnalités", "Implémenter le stockage local (Room)",
        "Builder l’application (APK)", "Tester sur l’appareil", "Installer et vérifier le bon fonctionnement",
    )
    val eta = listOf("~ 4 min", "~ 3-4 min", "~ 2-3 min", "~ 2 min", "~ 1 min", "< 1 min")

    val code = listOf(
        "@Database(entities = [Note::class], version = 1)",
        "abstract class NoteDatabase : RoomDatabase() {",
        "    abstract fun noteDao(): NoteDao",
        "    companion object {",
        "        @Volatile",
        "        private var INSTANCE: NoteDatabase? = null",
        "        fun getDatabase(context: Context): NoteDatabase {",
        "            return INSTANCE ?: synchronized(this) {",
        "                INSTANCE ?: Room.databaseBuilder(",
        "                    context.applicationContext,",
        "                    NoteDatabase::class.java,",
        "                    \"notes_db\"",
        "                ).build().also { INSTANCE = it }",
        "            }",
        "        }",
        "    }",
        "}",
    ).joinToString("\n")

    val initialLogs = listOf(
        LogLine("14:25:12", LogKind.Run, "Task :app:compileDebugKotlin ..."), LogLine("14:25:13", LogKind.Run, "Task :app:mergeDebugResources"),
        LogLine("14:25:14", LogKind.Run, "Task :app:processDebugResources"), LogLine("14:25:16", LogKind.Run, "Task :app:generateDebugBuildConfig"),
        LogLine("14:25:17", LogKind.Run, "Task :app:assembleDebug"), LogLine("14:25:18", LogKind.Ok, "APK généré avec succès"),
        LogLine("14:25:19", LogKind.Dir, "Installation sur l’appareil…"), LogLine("14:25:21", LogKind.Ok, "Application installée"),
        LogLine("14:25:22", LogKind.Run, "Lancement des tests d’interface…"), LogLine("14:25:24", LogKind.Ok, "Tests OK"),
    )

    const val userMessage = "Peux-tu créer une petite application Android de prise de notes, la builder, la tester sur l’appareil et l’installer ?\nUtilise un design moderne et stocke les notes localement."
    const val intro = "Bien sûr ! Je vais créer une application Android de prise de notes complète, la compiler, la tester et l’installer sur votre appareil. Voici mon plan d’action :"
    const val progressText = "J’ai déjà initialisé le projet et mis en place la structure. Je commence maintenant l’implémentation de la base de données locale avec Room."
    const val liveMessage = "La base de données est en place. Je continue avec le DAO et le repository…"

    /** Initial state of the prototype: step 3/6 running, logs 14:25:12 → 14:25:24. */
    val initialRun = TaskRunUi(RunStatus.Running, step = 2, stepCount = 6, progress = 0.54f, logs = initialLogs, approval = Approval.None, liveMessage = liveMessage, eta = eta[2])

    fun planSteps(run: TaskRunUi): List<PlanStep> = steps.mapIndexed { i, t ->
        val done = run.status == RunStatus.Done || i < run.step
        val active = run.status != RunStatus.Done && i == run.step
        PlanStep(t, when { done -> StepState.Done; active && run.status == RunStatus.Running -> StepState.Running; active -> StepState.Waiting; else -> StepState.Upcoming })
    }
}
