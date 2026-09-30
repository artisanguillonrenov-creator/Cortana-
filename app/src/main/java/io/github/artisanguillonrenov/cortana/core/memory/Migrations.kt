package io.github.artisanguillonrenov.cortana.core.memory

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.artisanguillonrenov.cortana.util.CLog
import java.io.File

/**
 * Explicit, tested Room migrations (production pack rule 9: never destructive).
 * Each migration keeps every existing row; see docs/DATA_MIGRATIONS.md.
 */
object Migrations {

    /** v1 → v2: durable agent runtime (task columns + events, plans, checkpoints, ledger, approvals, grants, outbox, artifacts). */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // tasks: new nullable/defaulted columns (ALTER TABLE keeps every row)
            db.execSQL("ALTER TABLE `tasks` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'chat'")
            db.execSQL("ALTER TABLE `tasks` ADD COLUMN `parentTaskId` TEXT")
            db.execSQL("ALTER TABLE `tasks` ADD COLUMN `planId` TEXT")
            db.execSQL("ALTER TABLE `tasks` ADD COLUMN `currentStepId` TEXT")
            db.execSQL("ALTER TABLE `tasks` ADD COLUMN `traceId` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `tasks` ADD COLUMN `stepCount` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `tasks` ADD COLUMN `replanCount` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `tasks` ADD COLUMN `errorJson` TEXT")
            db.execSQL("ALTER TABLE `tasks` ADD COLUMN `requestJson` TEXT")
            db.execSQL("ALTER TABLE `tasks` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `tasks` ADD COLUMN `leaseOwner` TEXT")
            db.execSQL("ALTER TABLE `tasks` ADD COLUMN `leaseExpiresAt` INTEGER")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_state` ON `tasks` (`state`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_parentTaskId` ON `tasks` (`parentTaskId`)")
            // Backfill: every task gets a trace id and an update time.
            db.execSQL("UPDATE `tasks` SET `traceId` = `id` WHERE `traceId` = ''")
            db.execSQL("UPDATE `tasks` SET `updatedAt` = COALESCE(`endedAt`, `createdAt`)")
            // v1 states → v2 vocabulary (termination reason kept separate from state, §7.2).
            db.execSQL("UPDATE `tasks` SET `state` = 'failed', `terminationReason` = 'budget_exhausted: ' || COALESCE(`terminationReason`, '') WHERE `state` = 'limit'")
            db.execSQL("UPDATE `tasks` SET `state` = 'completed', `terminationReason` = 'question_asked_v1: ' || COALESCE(`terminationReason`, '') WHERE `state` = 'waiting_user'")
            db.execSQL("UPDATE `tasks` SET `state` = 'interrupted' WHERE `state` = 'running'")

            db.execSQL("CREATE TABLE IF NOT EXISTS `task_events` (`eventId` TEXT NOT NULL, `taskId` TEXT NOT NULL, `fromState` TEXT, `toState` TEXT NOT NULL, `actor` TEXT NOT NULL, `reason` TEXT NOT NULL, `at` INTEGER NOT NULL, `traceId` TEXT NOT NULL, `stepId` TEXT, `toolCallId` TEXT, PRIMARY KEY(`eventId`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_events_taskId` ON `task_events` (`taskId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_events_at` ON `task_events` (`at`)")
            // One synthetic event per legacy task so every task has an auditable history.
            db.execSQL(
                "INSERT INTO `task_events` (`eventId`, `taskId`, `fromState`, `toState`, `actor`, `reason`, `at`, `traceId`) " +
                    "SELECT 'mig-' || `id`, `id`, NULL, `state`, 'migration', 'migrated from database v1', `updatedAt`, `traceId` FROM `tasks`"
            )

            db.execSQL("CREATE TABLE IF NOT EXISTS `plans` (`planId` TEXT NOT NULL, `taskId` TEXT NOT NULL, `version` INTEGER NOT NULL, `strategy` TEXT NOT NULL, `planJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `active` INTEGER NOT NULL, PRIMARY KEY(`planId`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_plans_taskId` ON `plans` (`taskId`)")

            db.execSQL("CREATE TABLE IF NOT EXISTS `checkpoints` (`checkpointId` TEXT NOT NULL, `taskId` TEXT NOT NULL, `planId` TEXT, `planVersion` INTEGER NOT NULL, `state` TEXT NOT NULL, `checkpointJson` TEXT NOT NULL, `reason` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`checkpointId`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_checkpoints_taskId` ON `checkpoints` (`taskId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_checkpoints_createdAt` ON `checkpoints` (`createdAt`)")

            db.execSQL("CREATE TABLE IF NOT EXISTS `task_notebooks` (`taskId` TEXT NOT NULL, `notebookJson` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`taskId`))")

            db.execSQL("CREATE TABLE IF NOT EXISTS `idempotency_ledger` (`key` TEXT NOT NULL, `taskId` TEXT NOT NULL, `capability` TEXT NOT NULL, `argsHash` TEXT NOT NULL, `status` TEXT NOT NULL, `receipt` TEXT, `outcomeRef` TEXT, `reversible` INTEGER NOT NULL, `undoRef` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_idempotency_ledger_taskId` ON `idempotency_ledger` (`taskId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_idempotency_ledger_status` ON `idempotency_ledger` (`status`)")
            // Carry the v1 anti-repeat records (successful keyed tool calls) into the ledger.
            db.execSQL(
                "INSERT OR IGNORE INTO `idempotency_ledger` (`key`, `taskId`, `capability`, `argsHash`, `status`, `receipt`, `outcomeRef`, `reversible`, `undoRef`, `createdAt`, `updatedAt`) " +
                    "SELECT `taskId` || ':' || `idempotencyKey`, `taskId`, `capability`, `idempotencyKey`, 'succeeded', NULL, `outputRef`, 0, NULL, `createdAt`, `createdAt` " +
                    "FROM `tool_calls` WHERE `idempotencyKey` IS NOT NULL AND `outcome` = 'ok'"
            )

            db.execSQL("CREATE TABLE IF NOT EXISTS `approvals` (`approvalId` TEXT NOT NULL, `taskId` TEXT, `capability` TEXT NOT NULL, `bindingHash` TEXT NOT NULL, `risk` TEXT NOT NULL, `status` TEXT NOT NULL, `detailsJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `decidedAt` INTEGER, PRIMARY KEY(`approvalId`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_approvals_taskId` ON `approvals` (`taskId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_approvals_createdAt` ON `approvals` (`createdAt`)")

            db.execSQL("CREATE TABLE IF NOT EXISTS `grants` (`grantId` TEXT NOT NULL, `capability` TEXT NOT NULL, `scope` TEXT, `constraintsJson` TEXT NOT NULL, `taskId` TEXT, `sessionId` TEXT, `scheduleId` TEXT, `validFrom` INTEGER NOT NULL, `validUntil` INTEGER, `maxUses` INTEGER, `uses` INTEGER NOT NULL, `revoked` INTEGER NOT NULL, `createdBy` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`grantId`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_grants_capability` ON `grants` (`capability`)")

            db.execSQL("CREATE TABLE IF NOT EXISTS `outbox` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, `dedupeKey` TEXT NOT NULL, `status` TEXT NOT NULL, `attempts` INTEGER NOT NULL, `nextAttemptAt` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `deliveredAt` INTEGER, `lastError` TEXT, PRIMARY KEY(`id`))")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_outbox_dedupeKey` ON `outbox` (`dedupeKey`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_outbox_status` ON `outbox` (`status`)")

            db.execSQL("CREATE TABLE IF NOT EXISTS `artifacts` (`artifactId` TEXT NOT NULL, `type` TEXT NOT NULL, `mime` TEXT NOT NULL, `name` TEXT NOT NULL, `uri` TEXT NOT NULL, `sha256` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `producerTaskId` TEXT, `producerCapability` TEXT, `sourceIdsJson` TEXT NOT NULL, `metadataJson` TEXT NOT NULL, `retention` TEXT NOT NULL DEFAULT 'keep', `createdAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`artifactId`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_artifacts_producerTaskId` ON `artifacts` (`producerTaskId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_artifacts_createdAt` ON `artifacts` (`createdAt`)")
            // Context Engine v2 (phase 6)
            db.execSQL("CREATE TABLE IF NOT EXISTS `conversation_summaries` (`sessionId` TEXT NOT NULL, `coveredUntil` INTEGER NOT NULL, `coveredCount` INTEGER NOT NULL, `summary` TEXT NOT NULL, `method` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`sessionId`))")
            // Hybrid memory (phase 7): derived vector index (filled at startup by MemoryIndexer) + relations.
            db.execSQL("CREATE TABLE IF NOT EXISTS `memory_vectors` (`memoryId` TEXT NOT NULL, `fingerprint` TEXT NOT NULL, `dim` INTEGER NOT NULL, `vector` BLOB NOT NULL, `textHash` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`memoryId`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_vectors_fingerprint` ON `memory_vectors` (`fingerprint`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `memory_edges` (`fromId` TEXT NOT NULL, `toId` TEXT NOT NULL, `relation` TEXT NOT NULL, `weight` REAL NOT NULL, `entity` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`fromId`, `toId`, `relation`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_edges_toId` ON `memory_edges` (`toId`)")
            db.execSQL(
                "INSERT OR IGNORE INTO `memory_edges` (`fromId`, `toId`, `relation`, `weight`, `entity`, `createdAt`) " +
                    "SELECT `id`, `supersedesId`, 'supersedes', 1.0, NULL, `updatedAt` FROM `memories` WHERE `supersedesId` IS NOT NULL"
            )
            // Procedural memory (phase 8).
            db.execSQL("CREATE TABLE IF NOT EXISTS `skills` (`skillId` TEXT NOT NULL, `name` TEXT NOT NULL, `currentVersion` INTEGER NOT NULL, `lifecycle` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `signature` TEXT, `successCount` INTEGER NOT NULL, `failureCount` INTEGER NOT NULL, `consecutiveFailures` INTEGER NOT NULL, `confidence` REAL NOT NULL, `lastValidatedAt` INTEGER, `lastUsedAt` INTEGER, `lastFailureReason` TEXT, `createdFrom` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`skillId`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_skills_signature` ON `skills` (`signature`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_skills_lifecycle` ON `skills` (`lifecycle`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `skill_versions` (`skillId` TEXT NOT NULL, `version` INTEGER NOT NULL, `definitionJson` TEXT NOT NULL, `note` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`skillId`, `version`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `skill_runs` (`runId` TEXT NOT NULL, `skillId` TEXT NOT NULL, `version` INTEGER NOT NULL, `taskId` TEXT, `kind` TEXT NOT NULL, `outcome` TEXT NOT NULL, `failedStep` INTEGER, `reason` TEXT, `at` INTEGER NOT NULL, PRIMARY KEY(`runId`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_skill_runs_skillId` ON `skill_runs` (`skillId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `skill_trajectories` (`taskId` TEXT NOT NULL, `signature` TEXT NOT NULL, `objective` TEXT NOT NULL, `stepsJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`taskId`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_skill_trajectories_signature` ON `skill_trajectories` (`signature`)")
            // Developer workspaces and change sets (phase 9).
            db.execSQL("CREATE TABLE IF NOT EXISTS `workspaces` (`workspaceId` TEXT NOT NULL, `name` TEXT NOT NULL, `rootPath` TEXT NOT NULL, `origin` TEXT NOT NULL, `backendId` TEXT NOT NULL, `vcsType` TEXT, `currentBranch` TEXT, `baseRevision` TEXT, `writable` INTEGER NOT NULL, `trust` TEXT NOT NULL, `detectedStacksJson` TEXT NOT NULL, `buildSystemsJson` TEXT NOT NULL, `profileJson` TEXT, `lockTaskId` TEXT, `lockUntil` INTEGER, `createdAt` INTEGER NOT NULL, `lastOpenedAt` INTEGER NOT NULL, PRIMARY KEY(`workspaceId`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `changesets` (`changeSetId` TEXT NOT NULL, `workspaceId` TEXT NOT NULL, `patchId` TEXT NOT NULL, `taskId` TEXT, `changeSetJson` TEXT NOT NULL, `backupDir` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`changeSetId`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_changesets_workspaceId` ON `changesets` (`workspaceId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_changesets_taskId` ON `changesets` (`taskId`)")
            // Paired workers (phase 12).
            db.execSQL("CREATE TABLE IF NOT EXISTS `workers` (`workerId` TEXT NOT NULL, `name` TEXT NOT NULL, `baseUrl` TEXT NOT NULL, `certificateSha256` TEXT NOT NULL, `workerPublicKey` TEXT NOT NULL, `capabilitiesJson` TEXT NOT NULL, `pairedAt` INTEGER NOT NULL, `lastSeenAt` INTEGER, `lastError` TEXT, `revoked` INTEGER NOT NULL, PRIMARY KEY(`workerId`))")
            // Plugins (phase 22).
            db.execSQL("CREATE TABLE IF NOT EXISTS `plugins` (`pluginId` TEXT NOT NULL, `name` TEXT NOT NULL, `publisher` TEXT NOT NULL, `keyFingerprint` TEXT NOT NULL, `publicKey` TEXT NOT NULL, `activeVersion` TEXT NOT NULL, `state` TEXT NOT NULL, `manifestJson` TEXT NOT NULL, `secretHandlesJson` TEXT NOT NULL, `installedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `lastError` TEXT, PRIMARY KEY(`pluginId`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `plugin_versions` (`pluginId` TEXT NOT NULL, `version` TEXT NOT NULL, `packageSha256` TEXT NOT NULL, `installedAt` INTEGER NOT NULL, `removedAt` INTEGER, PRIMARY KEY(`pluginId`, `version`))")
            // Durable scheduled runs and per-schedule concurrency policy (phase 27).
            db.execSQL("ALTER TABLE `schedules` ADD COLUMN `concurrencyPolicy` TEXT NOT NULL DEFAULT 'skip'")
            db.execSQL("CREATE TABLE IF NOT EXISTS `schedule_runs` (`runId` TEXT NOT NULL, `scheduleId` TEXT NOT NULL, `dueAt` INTEGER NOT NULL, `queuedAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `startedAt` INTEGER, `finishedAt` INTEGER, `taskId` TEXT, `detail` TEXT, `late` INTEGER NOT NULL, PRIMARY KEY(`runId`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_schedule_runs_scheduleId` ON `schedule_runs` (`scheduleId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_schedule_runs_status` ON `schedule_runs` (`status`)")
            // Phase 27: improvement proposals and regression cases.
            db.execSQL("CREATE TABLE IF NOT EXISTS `improvement_proposals` (`proposalId` TEXT NOT NULL, `fingerprint` TEXT NOT NULL, `kind` TEXT NOT NULL, `title` TEXT NOT NULL, `rationale` TEXT NOT NULL, `evidenceJson` TEXT NOT NULL, `changeJson` TEXT, `status` TEXT NOT NULL, `version` INTEGER NOT NULL, `analyzer` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `decidedAt` INTEGER, `decidedBy` TEXT, `previousJson` TEXT, PRIMARY KEY(`proposalId`))")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_improvement_proposals_fingerprint` ON `improvement_proposals` (`fingerprint`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_improvement_proposals_status` ON `improvement_proposals` (`status`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `eval_cases` (`caseId` TEXT NOT NULL, `objectiveKey` TEXT NOT NULL, `objective` TEXT NOT NULL, `expectedJson` TEXT NOT NULL, `sourceProposalId` TEXT, `createdAt` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, PRIMARY KEY(`caseId`))")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_eval_cases_objectiveKey` ON `eval_cases` (`objectiveKey`)")
            // Phase 28: persisted trace spans.
            db.execSQL("CREATE TABLE IF NOT EXISTS `spans` (`spanId` TEXT NOT NULL, `traceId` TEXT NOT NULL, `parentSpanId` TEXT, `name` TEXT NOT NULL, `taskId` TEXT, `startMs` INTEGER NOT NULL, `endMs` INTEGER NOT NULL, `status` TEXT NOT NULL, `errorType` TEXT, `attributesJson` TEXT NOT NULL, `exported` INTEGER NOT NULL, PRIMARY KEY(`spanId`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_spans_taskId` ON `spans` (`taskId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_spans_startMs` ON `spans` (`startMs`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_spans_exported` ON `spans` (`exported`)")
            // External connections (phase 26).
            db.execSQL("CREATE TABLE IF NOT EXISTS `connections` (`connectionId` TEXT NOT NULL, `kind` TEXT NOT NULL, `name` TEXT NOT NULL, `authScheme` TEXT NOT NULL, `configJson` TEXT NOT NULL, `secretHandlesJson` TEXT NOT NULL, `scopesJson` TEXT NOT NULL, `state` TEXT NOT NULL, `health` TEXT NOT NULL, `lastHealthAt` INTEGER, `lastError` TEXT, `failures` INTEGER NOT NULL, `pluginId` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`connectionId`))")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_connections_name` ON `connections` (`name`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `connection_events` (`id` TEXT NOT NULL, `connectionId` TEXT NOT NULL, `at` INTEGER NOT NULL, `type` TEXT NOT NULL, `outcome` TEXT NOT NULL, `detail` TEXT NOT NULL, `taskId` TEXT, `payload` TEXT, PRIMARY KEY(`id`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_connection_events_connectionId` ON `connection_events` (`connectionId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_connection_events_at` ON `connection_events` (`at`)")
        }
    }

    /**
     * v2 → v3: Cognitive Council tables (D-20260929-067, doc 06 §6.1, doc 18 §18.3). Purely additive —
     * no existing table or row changes, so a v2 database keeps every conversation, memory and task.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `council_runs` (`id` TEXT NOT NULL, `parentTaskId` TEXT NOT NULL, `mode` TEXT NOT NULL, `presetId` TEXT, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `completedAt` INTEGER, `configSnapshotJson` TEXT NOT NULL, `terminationReason` TEXT, `totalTokens` INTEGER NOT NULL, `totalCostMicros` INTEGER, `wallTimeMs` INTEGER NOT NULL, `summaryJson` TEXT, `metricsJson` TEXT, PRIMARY KEY(`id`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_council_runs_parentTaskId` ON `council_runs` (`parentTaskId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_council_runs_createdAt` ON `council_runs` (`createdAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_council_runs_status` ON `council_runs` (`status`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `council_agent_slots` (`id` TEXT NOT NULL, `runId` TEXT NOT NULL, `roleProfileId` TEXT NOT NULL, `modelRouteSnapshot` TEXT NOT NULL, `toolScopeHash` TEXT NOT NULL, `required` INTEGER NOT NULL, `status` TEXT NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_council_agent_slots_runId` ON `council_agent_slots` (`runId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `council_rounds` (`id` TEXT NOT NULL, `runId` TEXT NOT NULL, `roundIndex` INTEGER NOT NULL, `roundType` TEXT NOT NULL, `divergenceScore` REAL, `agreementScore` REAL, `evidenceCoverageScore` REAL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER, PRIMARY KEY(`id`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_council_rounds_runId_roundIndex` ON `council_rounds` (`runId`, `roundIndex`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `council_contributions` (`id` TEXT NOT NULL, `roundId` TEXT NOT NULL, `agentSlotId` TEXT NOT NULL, `candidateKey` TEXT, `structuredSummaryJson` TEXT NOT NULL, `confidenceFeaturesJson` TEXT NOT NULL, `tokenInput` INTEGER NOT NULL, `tokenOutput` INTEGER NOT NULL, `latencyMs` INTEGER NOT NULL, `errorCode` TEXT, PRIMARY KEY(`id`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_council_contributions_roundId` ON `council_contributions` (`roundId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_council_contributions_agentSlotId` ON `council_contributions` (`agentSlotId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `council_claims` (`id` TEXT NOT NULL, `contributionId` TEXT NOT NULL, `normalizedText` TEXT NOT NULL, `claimType` TEXT NOT NULL, `confidenceScore` REAL, `tainted` INTEGER NOT NULL, `verificationStatus` TEXT NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_council_claims_contributionId` ON `council_claims` (`contributionId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_council_claims_verificationStatus` ON `council_claims` (`verificationStatus`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `council_evidence_refs` (`id` TEXT NOT NULL, `claimId` TEXT NOT NULL, `sourceType` TEXT NOT NULL, `sourceRef` TEXT NOT NULL, `toolCallId` TEXT, `trustClass` TEXT NOT NULL, `observedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_council_evidence_refs_claimId` ON `council_evidence_refs` (`claimId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `council_concerns` (`id` TEXT NOT NULL, `contributionId` TEXT NOT NULL, `severity` TEXT NOT NULL, `targetClaimId` TEXT, `summary` TEXT NOT NULL, `resolved` INTEGER NOT NULL, `resolutionRef` TEXT, PRIMARY KEY(`id`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_council_concerns_contributionId` ON `council_concerns` (`contributionId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `council_votes` (`id` TEXT NOT NULL, `roundId` TEXT NOT NULL, `agentSlotId` TEXT NOT NULL, `protocol` TEXT NOT NULL, `ballotJson` TEXT NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_council_votes_roundId` ON `council_votes` (`roundId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `council_decisions` (`id` TEXT NOT NULL, `runId` TEXT NOT NULL, `roundId` TEXT, `protocol` TEXT NOT NULL, `candidateKey` TEXT, `metricsJson` TEXT NOT NULL, `minorityReportJson` TEXT NOT NULL, `unresolvedConcernsJson` TEXT NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_council_decisions_runId` ON `council_decisions` (`runId`)")
        }
    }

    /**
     * v3 → v4: Chat Workspace (D-20260930-068). Additive: new columns with defaults and five new tables.
     * Every existing conversation becomes a single branch — each message's parent is the previous
     * message of its session (createdAt, then rowid) and the session's leaf is its newest message —
     * so what the owner and the model see is unchanged.
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // messages: the tree (parent, status, run, extras)
            db.execSQL("ALTER TABLE `messages` ADD COLUMN `parentId` TEXT")
            db.execSQL("ALTER TABLE `messages` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'complete'")
            db.execSQL("ALTER TABLE `messages` ADD COLUMN `runId` TEXT")
            db.execSQL("ALTER TABLE `messages` ADD COLUMN `metaJson` TEXT")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_parentId` ON `messages` (`parentId`)")
            // sessions: branch pointer and organisation
            db.execSQL("ALTER TABLE `sessions` ADD COLUMN `activeLeafId` TEXT")
            db.execSQL("ALTER TABLE `sessions` ADD COLUMN `projectId` TEXT")
            db.execSQL("ALTER TABLE `sessions` ADD COLUMN `mode` TEXT NOT NULL DEFAULT 'chat'")
            db.execSQL("ALTER TABLE `sessions` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `sessions` ADD COLUMN `archived` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `sessions` ADD COLUMN `tagsJson` TEXT NOT NULL DEFAULT '[]'")
            db.execSQL("ALTER TABLE `sessions` ADD COLUMN `settingsJson` TEXT NOT NULL DEFAULT '{}'")
            db.execSQL("ALTER TABLE `conversation_summaries` ADD COLUMN `coveredUntilMessageId` TEXT")
            // new tables
            db.execSQL("CREATE TABLE IF NOT EXISTS `projects` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `instructions` TEXT NOT NULL, `preferredModel` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `archived` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_projects_updatedAt` ON `projects` (`updatedAt`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `chat_drafts` (`sessionId` TEXT NOT NULL, `text` TEXT NOT NULL, `attachmentsJson` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`sessionId`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `chat_queue` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `text` TEXT NOT NULL, `attachmentsJson` TEXT NOT NULL, `position` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `status` TEXT NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_queue_sessionId` ON `chat_queue` (`sessionId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_queue_position` ON `chat_queue` (`position`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `chat_pins` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `targetType` TEXT NOT NULL, `targetId` TEXT NOT NULL, `label` TEXT NOT NULL, `text` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_pins_sessionId` ON `chat_pins` (`sessionId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `context_checkpoints` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `summary` TEXT NOT NULL, `coveredUntilMessageId` TEXT, `coveredCount` INTEGER NOT NULL, `method` TEXT NOT NULL, `modelRef` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_context_checkpoints_sessionId` ON `context_checkpoints` (`sessionId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_context_checkpoints_createdAt` ON `context_checkpoints` (`createdAt`)")
            // backfill: chain each session's messages in their historical order
            db.execSQL(
                "UPDATE `messages` SET `parentId` = (SELECT p.`id` FROM `messages` p WHERE p.`sessionId` = `messages`.`sessionId` " +
                    "AND (p.`createdAt` < `messages`.`createdAt` OR (p.`createdAt` = `messages`.`createdAt` AND p.`rowid` < `messages`.`rowid`)) " +
                    "ORDER BY p.`createdAt` DESC, p.`rowid` DESC LIMIT 1)"
            )
            db.execSQL(
                "UPDATE `sessions` SET `activeLeafId` = (SELECT m.`id` FROM `messages` m WHERE m.`sessionId` = `sessions`.`id` " +
                    "ORDER BY m.`createdAt` DESC, m.`rowid` DESC LIMIT 1)"
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
}

/**
 * Copies the database files before Room runs a migration (doc 01 §4 "export de sauvegarde avant
 * migration majeure"). Keeps the last 3 pre-migration copies.
 */
object PreMigrationBackup {
    fun runIfNeeded(context: Context, dbName: String, targetVersion: Int): File? {
        val dbFile = context.getDatabasePath(dbName)
        if (!dbFile.exists()) return null
        return try {
            val current = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                // Fold the WAL into the main file so a plain copy is consistent.
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                db.version
            }
            if (current >= targetVersion || current == 0) return null
            val dir = File(context.filesDir, "backups/pre-migration").apply { mkdirs() }
            val out = File(dir, "$dbName.v$current-to-v$targetVersion.${System.currentTimeMillis()}.db")
            dbFile.copyTo(out, overwrite = true)
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() }
            CLog.i("pre-migration backup written (v$current → v$targetVersion)")
            out
        } catch (t: Throwable) {
            CLog.e("pre-migration backup failed", t)
            null
        }
    }
}
