package com.faforever.userservice.backend.account

import com.faforever.userservice.backend.domain.AccountRequest
import com.faforever.userservice.backend.domain.AccountRequestRepository
import com.faforever.userservice.backend.domain.AccountRequestType
import jakarta.persistence.EntityManager
import jakarta.persistence.Query
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.OffsetDateTime

class AccountAnonymizationServiceTest {

    private companion object {
        private const val USER_ID = 1
        private const val USERNAME = "testUser"
        private const val EMAIL = "test@example.com"
    }

    private val entityManager: EntityManager = mock()
    private val accountRequestRepository: AccountRequestRepository = mock()
    private val createdSql = mutableListOf<String>()

    private val service = DatabaseAccountAnonymizationService(
        entityManager = entityManager,
        accountRequestRepository = accountRequestRepository,
    )

    @Test
    fun anonymizeUserWithGamesAnonymizesLoginAndKeepsLoginRow() {
        setupNativeQueryMocks(
            games = 5,
            bans = 0,
        )
        val pendingDeletion = buildPendingDeletion()

        val event = service.anonymizeUser(USER_ID, pendingDeletion)

        assertThat(event.userId, equalTo(USER_ID))
        assertThat(event.username, equalTo(USERNAME))
        assertThat(event.email, equalTo(EMAIL))

        verify(accountRequestRepository).delete(pendingDeletion)

        assertThat(createdSql, hasItem(containsString("UPDATE login")))
        assertThat(createdSql, hasItem(containsString("UPDATE service_links")))
        assertThat(createdSql, not(hasItem(equalTo("DELETE FROM login WHERE id = :userId"))))
    }

    @Test
    fun anonymizeUserWithoutGamesDeletesRemovableDataAndKeepsAnonymizedLoginRow() {
        setupNativeQueryMocks(
            games = 0,
            bans = 0,
        )
        val pendingDeletion = buildPendingDeletion()

        val event = service.anonymizeUser(USER_ID, pendingDeletion)

        assertThat(event.userId, equalTo(USER_ID))
        assertThat(event.username, equalTo(USERNAME))
        assertThat(event.email, equalTo(EMAIL))

        verify(accountRequestRepository).delete(pendingDeletion)

        assertThat(createdSql, hasItem(containsString("DELETE FROM service_links")))
        assertThat(createdSql, hasItem(containsString("DELETE FROM leaderboard_rating")))
        assertThat(createdSql, hasItem(containsString("UPDATE login")))
        assertThat(createdSql, not(hasItem(containsString("DELETE FROM login WHERE id = :userId"))))
    }

    @Test
    fun anonymizeUserWithBansStillAnonymizesAccount() {
        setupNativeQueryMocks(
            games = 5,
            bans = 2,
        )
        val pendingDeletion = buildPendingDeletion()

        val event = service.anonymizeUser(USER_ID, pendingDeletion)

        assertThat(event.userId, equalTo(USER_ID))

        verify(accountRequestRepository).delete(pendingDeletion)
        assertThat(createdSql, hasItem(containsString("UPDATE login")))
    }

    @Test
    fun anonymizeUserThrowsWhenUserDoesNotExist() {
        setupNativeQueryMocksForMissingUser()
        val pendingDeletion = buildPendingDeletion()

        try {
            service.anonymizeUser(USER_ID, pendingDeletion)
        } catch (exception: AccountAnonymizationUserNotFoundException) {
            assertThat(exception.message, containsString("user id $USER_ID"))
            return
        }

        error("Expected AccountAnonymizationUserNotFoundException")
    }

    private fun setupNativeQueryMocks(
        games: Long,
        bans: Long,
    ) {
        whenever(entityManager.createNativeQuery(any<String>())).thenAnswer { invocation ->
            val sql = invocation.arguments[0] as String
            createdSql.add(sql.trim())

            when {
                sql.contains("SELECT id, login, email") -> queryReturningList(
                    listOf(arrayOf(USER_ID, USERNAME, EMAIL)),
                )

                sql.contains("FROM game_player_stats") -> queryReturningSingleResult(games)

                sql.contains("FROM ban") -> queryReturningSingleResult(bans)

                else -> updateQuery()
            }
        }
    }

    private fun setupNativeQueryMocksForMissingUser() {
        whenever(entityManager.createNativeQuery(any<String>())).thenAnswer { invocation ->
            val sql = invocation.arguments[0] as String
            createdSql.add(sql.trim())

            when {
                sql.contains("SELECT id, login, email") -> queryReturningList(emptyList<Array<Any>>())
                else -> updateQuery()
            }
        }
    }

    private fun queryReturningList(result: List<Array<Any>>): Query {
        val query: Query = mock()
        whenever(query.setParameter(any<String>(), any())).thenReturn(query)
        whenever(query.resultList).thenReturn(result)
        return query
    }

    private fun queryReturningSingleResult(result: Long): Query {
        val query: Query = mock()
        whenever(query.setParameter(any<String>(), any())).thenReturn(query)
        whenever(query.singleResult).thenReturn(result)
        return query
    }

    private fun updateQuery(): Query {
        val query: Query = mock()
        whenever(query.setParameter(any<String>(), any())).thenReturn(query)
        whenever(query.executeUpdate()).thenReturn(1)
        return query
    }

    private fun buildPendingDeletion() = AccountRequest(
        id = "deletion-id",
        userId = USER_ID,
        type = AccountRequestType.ACCOUNT_DELETION,
        tokenHash = "hash",
        expiresAt = OffsetDateTime.now().plusHours(1),
        data = emptyMap(),
    )
}
