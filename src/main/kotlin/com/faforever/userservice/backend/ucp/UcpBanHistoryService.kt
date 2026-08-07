package com.faforever.userservice.backend.ucp

import com.faforever.userservice.backend.domain.Ban
import com.faforever.userservice.backend.domain.BanLevel
import com.faforever.userservice.backend.domain.BanRepository
import com.faforever.userservice.backend.domain.UserRepository
import jakarta.enterprise.context.ApplicationScoped
import java.time.OffsetDateTime

enum class UcpBanStatus {
    ACTIVE,
    EXPIRED,
    REVOKED,
}

data class UcpBanHistoryEntry(
    val authorId: Int,
    val authorUsername: String?,
    val level: BanLevel,
    val reason: String,
    val createTime: OffsetDateTime,
    val expiresAt: OffsetDateTime?,
    val status: UcpBanStatus,
    val revokeTime: OffsetDateTime?,
    val revokeReason: String?,
    val revokeAuthorId: Int?,
    val revokeAuthorUsername: String?,
)

@ApplicationScoped
class UcpBanHistoryService(
    private val banRepository: BanRepository,
    private val userRepository: UserRepository,
) {
    fun getBanHistoryForUser(userId: Int): List<UcpBanHistoryEntry> {
        val bans = banRepository.findByPlayerIdOrderByCreateTimeDesc(userId)

        return bans.map { ban ->
            val authorUsername = userRepository.findById(ban.authorId)?.username

            val revokeAuthorUsername = ban.revokeAuthorId?.let { revokeAuthorId ->
                userRepository.findById(revokeAuthorId)?.username
            }

            ban.toHistoryEntry(
                authorUsername = authorUsername,
                revokeAuthorUsername = revokeAuthorUsername,
            )
        }
    }

    private fun Ban.toHistoryEntry(
        authorUsername: String?,
        revokeAuthorUsername: String?,
    ): UcpBanHistoryEntry {
        return UcpBanHistoryEntry(
            authorId = authorId,
            authorUsername = authorUsername,
            level = level,
            reason = reason,
            createTime = createTime,
            expiresAt = expiresAt,
            status = getStatus(),
            revokeTime = revokeTime,
            revokeReason = revokeReason,
            revokeAuthorId = revokeAuthorId,
            revokeAuthorUsername = revokeAuthorUsername,
        )
    }

    private fun Ban.getStatus(): UcpBanStatus {
        return when {
            revokeTime != null -> UcpBanStatus.REVOKED
            isActive -> UcpBanStatus.ACTIVE
            else -> UcpBanStatus.EXPIRED
        }
    }
}
