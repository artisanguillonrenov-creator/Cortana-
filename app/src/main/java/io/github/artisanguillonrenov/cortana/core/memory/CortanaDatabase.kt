package io.github.artisanguillonrenov.cortana.core.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SessionEntity::class, MessageEntity::class, MessageFts::class,
        TaskEntity::class, StepEntity::class, ToolCallEntity::class,
        MemoryEntity::class, MemoryFts::class,
        ScheduleEntity::class, AuditEntity::class,
        ProviderEntity::class, ModelCapEntity::class, UsageEntity::class,
        SettingEntity::class,
        // v2
        TaskEventEntity::class, PlanEntity::class, CheckpointEntity::class, TaskNotebookEntity::class,
        IdempotencyEntity::class, ApprovalEntity::class, GrantEntity::class, OutboxEntity::class, ArtifactEntity::class,
        ConversationSummaryEntity::class, MemoryVectorEntity::class, MemoryEdgeEntity::class,
        SkillEntity::class, SkillVersionEntity::class, SkillRunEntity::class, SkillTrajectoryEntity::class,
        WorkspaceEntity::class, ChangeSetEntity::class, WorkerEntity::class,
        PluginEntity::class, PluginVersionEntity::class,
        ConnectionEntity::class, ConnectionEventEntity::class,
        ScheduleRunEntity::class,
        ImprovementProposalEntity::class, EvalCaseEntity::class,
        SpanEntity::class,
        // v3: Cognitive Council (D-20260929-067)
        CouncilRunEntity::class, CouncilAgentSlotEntity::class, CouncilRoundEntity::class, CouncilContributionEntity::class,
        CouncilClaimEntity::class, CouncilEvidenceRefEntity::class, CouncilConcernEntity::class, CouncilVoteEntity::class, CouncilDecisionEntity::class,
        // v4: Chat Workspace (D-20260930-068)
        ProjectEntity::class, ChatDraftEntity::class, ChatQueueEntity::class, ChatPinEntity::class, ContextCheckpointEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class CortanaDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun messages(): MessageDao
    abstract fun tasks(): TaskDao
    abstract fun memories(): MemoryDao
    abstract fun schedules(): ScheduleDao
    abstract fun audit(): AuditDao
    abstract fun providers(): ProviderDao
    abstract fun usage(): UsageDao
    abstract fun settings(): SettingDao
    abstract fun runtime(): RuntimeDao
    abstract fun skills(): SkillDao
    abstract fun dev(): DevDao
    abstract fun workers(): WorkerDao
    abstract fun plugins(): PluginDao
    abstract fun connections(): ConnectionDao
    abstract fun improvements(): ImprovementDao
    abstract fun spans(): SpanDao
    abstract fun council(): CouncilDao
    abstract fun chat(): ChatDao

    companion object {
        const val NAME = "cortana.db"
        const val VERSION = 4

        fun build(context: Context): CortanaDatabase {
            PreMigrationBackup.runIfNeeded(context, NAME, VERSION)
            return Room.databaseBuilder(context, CortanaDatabase::class.java, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(*Migrations.ALL)
                .build()
        }
    }
}
