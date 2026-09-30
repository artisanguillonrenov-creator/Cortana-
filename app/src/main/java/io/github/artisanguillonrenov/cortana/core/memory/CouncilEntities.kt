package io.github.artisanguillonrenov.cortana.core.memory

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.serialization.Serializable

/*
 * Cognitive Council persistence (doc 06 §6.1, schema v3, D-20260929-067): structured summaries only.
 * Never stored: prompts, raw model outputs, private reasoning, provider reasoning blocks, secrets
 * (every text is sanitised and redacted by the parser before it reaches these rows).
 */

@Serializable
@Entity(tableName = "council_runs", indices = [Index("parentTaskId"), Index("createdAt"), Index("status")])
data class CouncilRunEntity(
    @PrimaryKey val id: String,
    val parentTaskId: String,
    val mode: String,
    val presetId: String?,
    val status: String,
    val createdAt: Long,
    val completedAt: Long? = null,
    /** The council configuration in force (redacted JSON). */
    val configSnapshotJson: String,
    val terminationReason: String? = null,
    val totalTokens: Int = 0,
    /** Null when the price is unknown (doc 08 §8.10): the token budget still applied. */
    val totalCostMicros: Long? = null,
    val wallTimeMs: Long = 0,
    /** The "Résumé du conseil" card (CouncilSummary JSON). */
    val summaryJson: String? = null,
    /** Per-run metrics feeding the council metrics (CouncilRunMetrics JSON). */
    val metricsJson: String? = null,
)

@Serializable
@Entity(tableName = "council_agent_slots", indices = [Index("runId")])
data class CouncilAgentSlotEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val roleProfileId: String,
    /** "provider · model" (+ fallbacks), never a key. */
    val modelRouteSnapshot: String,
    val toolScopeHash: String,
    val required: Boolean,
    val status: String,
)

@Serializable
@Entity(tableName = "council_rounds", indices = [Index(value = ["runId", "roundIndex"])])
data class CouncilRoundEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val roundIndex: Int,
    /** initial | critique | challenge | repair */
    val roundType: String,
    val divergenceScore: Double? = null,
    val agreementScore: Double? = null,
    val evidenceCoverageScore: Double? = null,
    val startedAt: Long,
    val endedAt: Long? = null,
)

@Serializable
@Entity(tableName = "council_contributions", indices = [Index("roundId"), Index("agentSlotId")])
data class CouncilContributionEntity(
    @PrimaryKey val id: String,
    val roundId: String,
    val agentSlotId: String,
    val candidateKey: String?,
    /** Candidate, assumptions, requested checks and a short public justification — no reasoning. */
    val structuredSummaryJson: String,
    val confidenceFeaturesJson: String,
    val tokenInput: Int,
    val tokenOutput: Int,
    val latencyMs: Long,
    val errorCode: String?,
)

@Serializable
@Entity(tableName = "council_claims", indices = [Index("contributionId"), Index("verificationStatus")])
data class CouncilClaimEntity(
    @PrimaryKey val id: String,
    val contributionId: String,
    val normalizedText: String,
    val claimType: String,
    val confidenceScore: Double?,
    val tainted: Boolean,
    val verificationStatus: String,
)

@Serializable
@Entity(tableName = "council_evidence_refs", indices = [Index("claimId")])
data class CouncilEvidenceRefEntity(
    @PrimaryKey val id: String,
    val claimId: String,
    /** tool | user | url | other */
    val sourceType: String,
    val sourceRef: String,
    val toolCallId: String?,
    /** trusted | untrusted | unverified */
    val trustClass: String,
    val observedAt: Long,
)

@Serializable
@Entity(tableName = "council_concerns", indices = [Index("contributionId")])
data class CouncilConcernEntity(
    @PrimaryKey val id: String,
    val contributionId: String,
    val severity: String,
    val targetClaimId: String?,
    val summary: String,
    val resolved: Boolean,
    val resolutionRef: String?,
)

@Serializable
@Entity(tableName = "council_votes", indices = [Index("roundId")])
data class CouncilVoteEntity(
    @PrimaryKey val id: String,
    val roundId: String,
    val agentSlotId: String,
    val protocol: String,
    val ballotJson: String,
)

@Serializable
@Entity(tableName = "council_decisions", indices = [Index("runId")])
data class CouncilDecisionEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val roundId: String?,
    val protocol: String,
    val candidateKey: String?,
    val metricsJson: String,
    val minorityReportJson: String,
    val unresolvedConcernsJson: String,
)

@Dao
interface CouncilDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertRun(r: CouncilRunEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSlots(s: List<CouncilAgentSlotEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertRound(r: CouncilRoundEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertContribution(c: CouncilContributionEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertClaims(c: List<CouncilClaimEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertEvidence(e: List<CouncilEvidenceRefEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertConcerns(c: List<CouncilConcernEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertVotes(v: List<CouncilVoteEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertDecision(d: CouncilDecisionEntity)

    @Query("SELECT * FROM council_runs WHERE id = :id") suspend fun run(id: String): CouncilRunEntity?
    @Query("SELECT * FROM council_runs WHERE parentTaskId = :taskId ORDER BY createdAt") suspend fun runsForTask(taskId: String): List<CouncilRunEntity>
    @Query("SELECT * FROM council_runs WHERE createdAt >= :since ORDER BY createdAt") suspend fun runsSince(since: Long): List<CouncilRunEntity>
    @Query("SELECT * FROM council_runs WHERE completedAt IS NULL") suspend fun unfinished(): List<CouncilRunEntity>
    @Query("SELECT COALESCE(SUM(totalTokens), 0) FROM council_runs WHERE createdAt >= :since") suspend fun tokensSince(since: Long): Int
    @Query("UPDATE council_runs SET status = :status WHERE id = :id") suspend fun setStatus(id: String, status: String)
    @Query("UPDATE council_runs SET status = :status, completedAt = :at, terminationReason = :reason, totalTokens = :tokens, totalCostMicros = :cost, wallTimeMs = :wall, summaryJson = :summary, metricsJson = :metrics WHERE id = :id")
    suspend fun finish(id: String, status: String, at: Long, reason: String, tokens: Int, cost: Long?, wall: Long, summary: String, metrics: String)
    @Query("UPDATE council_agent_slots SET status = :status WHERE id = :id") suspend fun setSlotStatus(id: String, status: String)
    @Query("UPDATE council_agent_slots SET status = :status WHERE runId = :runId AND status IN ('pending', 'running')") suspend fun closeSlots(runId: String, status: String)
    @Query("UPDATE council_runs SET presetId = :presetId WHERE id = :id") suspend fun setPreset(id: String, presetId: String)
    @Query("UPDATE council_rounds SET endedAt = :at, divergenceScore = :divergence, agreementScore = :agreement, evidenceCoverageScore = :coverage WHERE id = :id")
    suspend fun endRound(id: String, at: Long, divergence: Double, agreement: Double, coverage: Double)
    @Query("UPDATE council_claims SET verificationStatus = :status WHERE id = :id") suspend fun setClaimStatus(id: String, status: String)

    @Query("SELECT * FROM council_agent_slots WHERE runId = :runId ORDER BY id") suspend fun slots(runId: String): List<CouncilAgentSlotEntity>
    @Query("SELECT * FROM council_rounds WHERE runId = :runId ORDER BY roundIndex, startedAt") suspend fun rounds(runId: String): List<CouncilRoundEntity>
    @Query("SELECT * FROM council_contributions WHERE roundId IN (SELECT id FROM council_rounds WHERE runId = :runId)") suspend fun contributions(runId: String): List<CouncilContributionEntity>
    @Query("SELECT * FROM council_claims WHERE contributionId IN (SELECT c.id FROM council_contributions c JOIN council_rounds r ON c.roundId = r.id WHERE r.runId = :runId)")
    suspend fun claims(runId: String): List<CouncilClaimEntity>
    @Query("SELECT * FROM council_evidence_refs WHERE claimId IN (SELECT cl.id FROM council_claims cl JOIN council_contributions c ON cl.contributionId = c.id JOIN council_rounds r ON c.roundId = r.id WHERE r.runId = :runId)")
    suspend fun evidence(runId: String): List<CouncilEvidenceRefEntity>
    @Query("SELECT * FROM council_concerns WHERE contributionId IN (SELECT c.id FROM council_contributions c JOIN council_rounds r ON c.roundId = r.id WHERE r.runId = :runId)")
    suspend fun concerns(runId: String): List<CouncilConcernEntity>
    @Query("SELECT * FROM council_votes WHERE roundId IN (SELECT id FROM council_rounds WHERE runId = :runId)") suspend fun votes(runId: String): List<CouncilVoteEntity>
    @Query("SELECT * FROM council_decisions WHERE runId = :runId") suspend fun decisions(runId: String): List<CouncilDecisionEntity>
}
