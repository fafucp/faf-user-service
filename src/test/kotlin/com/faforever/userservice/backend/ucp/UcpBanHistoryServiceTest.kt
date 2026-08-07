package com.faforever.userservice.backend.ucp

import com.faforever.userservice.backend.domain.Ban
import com.faforever.userservice.backend.domain.BanLevel
import com.faforever.userservice.backend.domain.BanRepository
import io.quarkus.test.InjectMock
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.OffsetDateTime

@QuarkusTest
class UcpBanHistoryServiceTest {

    @Inject
    private lateinit var ucpBanHistoryService: UcpBanHistoryService

    @InjectMock
    private lateinit var banRepository: BanRepository

    @Test
    fun returnsEmptyBanHistoryWhenUserHasNoBans() {
        whenever(banRepository.findByPlayerIdOrderByCreateTimeDesc(USER_ID)).thenReturn(emptyList())

        val result = ucpBanHistoryService.getBanHistoryForUser(USER_ID)

        assertThat(result, empty())
        verify(banRepository).findByPlayerIdOrderByCreateTimeDesc(USER_ID)
    }

    @Test
    fun mapsActiveTemporaryBan() {
        val ban = buildBan(
            id = 1,
            level = BanLevel.GLOBAL,
            reason = "Temporary global ban",
            createTime = NOW.minusDays(1),
            expiresAt = NOW.plusDays(6),
        )
        whenever(banRepository.findByPlayerIdOrderByCreateTimeDesc(USER_ID)).thenReturn(listOf(ban))

        val result = ucpBanHistoryService.getBanHistoryForUser(USER_ID)

        assertThat(result, hasSize(1))

        val entry = result.single()
        assertThat(entry.level, equalTo(BanLevel.GLOBAL))
        assertThat(entry.reason, equalTo("Temporary global ban"))
        assertThat(entry.createTime, equalTo(ban.createTime))
        assertThat(entry.expiresAt, equalTo(ban.expiresAt))
        assertThat(entry.status, equalTo(UcpBanStatus.ACTIVE))
        assertThat(entry.revokeTime, nullValue())
        assertThat(entry.revokeReason, nullValue())
    }

    @Test
    fun mapsActivePermanentBan() {
        val ban = buildBan(
            id = 1,
            level = BanLevel.CHAT,
            reason = "Permanent chat ban",
            createTime = NOW.minusDays(3),
            expiresAt = null,
        )
        whenever(banRepository.findByPlayerIdOrderByCreateTimeDesc(USER_ID)).thenReturn(listOf(ban))

        val result = ucpBanHistoryService.getBanHistoryForUser(USER_ID)

        val entry = result.single()
        assertThat(entry.level, equalTo(BanLevel.CHAT))
        assertThat(entry.reason, equalTo("Permanent chat ban"))
        assertThat(entry.createTime, equalTo(ban.createTime))
        assertThat(entry.expiresAt, nullValue())
        assertThat(entry.status, equalTo(UcpBanStatus.ACTIVE))
        assertThat(entry.revokeTime, nullValue())
        assertThat(entry.revokeReason, nullValue())
    }

    @Test
    fun mapsExpiredBan() {
        val ban = buildBan(
            id = 1,
            level = BanLevel.VAULT,
            reason = "Expired vault ban",
            createTime = NOW.minusDays(10),
            expiresAt = NOW.minusDays(2),
        )
        whenever(banRepository.findByPlayerIdOrderByCreateTimeDesc(USER_ID)).thenReturn(listOf(ban))

        val result = ucpBanHistoryService.getBanHistoryForUser(USER_ID)

        val entry = result.single()
        assertThat(entry.level, equalTo(BanLevel.VAULT))
        assertThat(entry.reason, equalTo("Expired vault ban"))
        assertThat(entry.createTime, equalTo(ban.createTime))
        assertThat(entry.expiresAt, equalTo(ban.expiresAt))
        assertThat(entry.status, equalTo(UcpBanStatus.EXPIRED))
        assertThat(entry.revokeTime, nullValue())
        assertThat(entry.revokeReason, nullValue())
    }

    @Test
    fun mapsRevokedTemporaryBan() {
        val ban = buildBan(
            id = 1,
            level = BanLevel.GLOBAL,
            reason = "Revoked global ban",
            createTime = NOW.minusDays(5),
            expiresAt = NOW.plusDays(10),
            revokeTime = NOW.minusDays(1),
            revokeReason = "Appeal accepted",
        )
        whenever(banRepository.findByPlayerIdOrderByCreateTimeDesc(USER_ID)).thenReturn(listOf(ban))

        val result = ucpBanHistoryService.getBanHistoryForUser(USER_ID)

        val entry = result.single()
        assertThat(entry.level, equalTo(BanLevel.GLOBAL))
        assertThat(entry.reason, equalTo("Revoked global ban"))
        assertThat(entry.createTime, equalTo(ban.createTime))
        assertThat(entry.expiresAt, equalTo(ban.expiresAt))
        assertThat(entry.status, equalTo(UcpBanStatus.REVOKED))
        assertThat(entry.revokeTime, equalTo(ban.revokeTime))
        assertThat(entry.revokeReason, equalTo("Appeal accepted"))
    }

    @Test
    fun mapsRevokedPermanentBan() {
        val ban = buildBan(
            id = 1,
            level = BanLevel.CHAT,
            reason = "Revoked permanent chat ban",
            createTime = NOW.minusDays(20),
            expiresAt = null,
            revokeTime = NOW.minusDays(2),
            revokeReason = "Issued by mistake",
        )
        whenever(banRepository.findByPlayerIdOrderByCreateTimeDesc(USER_ID)).thenReturn(listOf(ban))

        val result = ucpBanHistoryService.getBanHistoryForUser(USER_ID)

        val entry = result.single()
        assertThat(entry.level, equalTo(BanLevel.CHAT))
        assertThat(entry.reason, equalTo("Revoked permanent chat ban"))
        assertThat(entry.createTime, equalTo(ban.createTime))
        assertThat(entry.expiresAt, nullValue())
        assertThat(entry.status, equalTo(UcpBanStatus.REVOKED))
        assertThat(entry.revokeTime, equalTo(ban.revokeTime))
        assertThat(entry.revokeReason, equalTo("Issued by mistake"))
    }

    @Test
    fun revokedStatusTakesPrecedenceOverExpiredStatus() {
        val ban = buildBan(
            id = 1,
            reason = "Revoked after expiry",
            createTime = NOW.minusDays(30),
            expiresAt = NOW.minusDays(20),
            revokeTime = NOW.minusDays(10),
            revokeReason = "Manual revoke",
        )
        whenever(banRepository.findByPlayerIdOrderByCreateTimeDesc(USER_ID)).thenReturn(listOf(ban))

        val result = ucpBanHistoryService.getBanHistoryForUser(USER_ID)

        assertThat(result.single().status, equalTo(UcpBanStatus.REVOKED))
    }

    @Test
    fun keepsRepositoryOrdering() {
        val newestBan = buildBan(
            id = 3,
            reason = "Newest ban",
            createTime = NOW.minusDays(1),
            expiresAt = NOW.plusDays(6),
        )
        val middleBan = buildBan(
            id = 2,
            reason = "Middle ban",
            createTime = NOW.minusDays(5),
            expiresAt = NOW.minusDays(1),
        )
        val oldestBan = buildBan(
            id = 1,
            reason = "Oldest ban",
            createTime = NOW.minusDays(20),
            expiresAt = null,
            revokeTime = NOW.minusDays(10),
        )
        whenever(banRepository.findByPlayerIdOrderByCreateTimeDesc(USER_ID)).thenReturn(
            listOf(newestBan, middleBan, oldestBan),
        )

        val result = ucpBanHistoryService.getBanHistoryForUser(USER_ID)

        assertThat(
            result.map { entry -> entry.reason },
            contains("Newest ban", "Middle ban", "Oldest ban"),
        )
    }

    @Test
    fun mapsAllBanLevels() {
        val globalBan = buildBan(id = 1, level = BanLevel.GLOBAL, reason = "Global ban")
        val chatBan = buildBan(id = 2, level = BanLevel.CHAT, reason = "Chat ban")
        val vaultBan = buildBan(id = 3, level = BanLevel.VAULT, reason = "Vault ban")
        whenever(banRepository.findByPlayerIdOrderByCreateTimeDesc(USER_ID)).thenReturn(
            listOf(globalBan, chatBan, vaultBan),
        )

        val result = ucpBanHistoryService.getBanHistoryForUser(USER_ID)

        assertThat(
            result.map { entry -> entry.level },
            contains(BanLevel.GLOBAL, BanLevel.CHAT, BanLevel.VAULT),
        )
    }

    private fun buildBan(
        id: Int,
        playerId: Int = USER_ID,
        authorId: Int = AUTHOR_ID,
        level: BanLevel = BanLevel.GLOBAL,
        reason: String = "Test ban",
        expiresAt: OffsetDateTime? = NOW.plusDays(7),
        revokeTime: OffsetDateTime? = null,
        reportId: Int? = null,
        revokeReason: String? = null,
        revokeAuthorId: Int? = null,
        createTime: OffsetDateTime = NOW.minusDays(1),
    ): Ban {
        return Ban(
            id = id,
            playerId = playerId,
            authorId = authorId,
            level = level,
            reason = reason,
            expiresAt = expiresAt,
            revokeTime = revokeTime,
            reportId = reportId,
            revokeReason = revokeReason,
            revokeAuthorId = revokeAuthorId,
            createTime = createTime,
        )
    }

    private companion object {
        const val USER_ID = 1
        const val AUTHOR_ID = 2

        val NOW: OffsetDateTime = OffsetDateTime.now()
    }
}
