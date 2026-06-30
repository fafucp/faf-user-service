package com.faforever.userservice.backend.account

import com.faforever.userservice.backend.domain.AccountRequest
import com.faforever.userservice.backend.domain.AccountRequestRepository
import com.faforever.userservice.backend.domain.AccountRequestType
import com.faforever.userservice.backend.domain.User
import com.faforever.userservice.backend.domain.UserRepository
import com.faforever.userservice.backend.email.EmailService
import com.faforever.userservice.backend.security.FafTokenService
import com.faforever.userservice.backend.security.FafTokenType
import com.faforever.userservice.config.FafProperties
import io.quarkus.test.InjectMock
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.temporal.TemporalAmount
import java.util.Base64

@QuarkusTest
class AccountDeletionServiceTest {

    @Inject
    private lateinit var accountDeletionService: AccountDeletionService

    @Inject
    private lateinit var fafProperties: FafProperties

    @InjectMock
    private lateinit var userRepository: UserRepository

    @InjectMock
    private lateinit var accountRequestRepository: AccountRequestRepository

    @InjectMock
    private lateinit var emailService: EmailService

    @InjectMock
    private lateinit var fafTokenService: FafTokenService

    @InjectMock
    private lateinit var accountAnonymizationService: AccountAnonymizationService

    @InjectMock
    private lateinit var accountDeletionEventPublisher: AccountDeletionEventPublisher

    @Test
    fun requestAccountDeletionCreatesPendingDeletionAndSendsConfirmation() {
        val user = buildTestUser()
        val token = "token"
        whenever(userRepository.findById(user.id!!)).thenReturn(user)
        whenever(
            fafTokenService.createToken(
                eq(FafTokenType.ACCOUNT_DELETION),
                any<TemporalAmount>(),
                any(),
            ),
        ).thenReturn(token)

        val result = accountDeletionService.requestAccountDeletion(user.id!!)

        assertThat(result, equalTo(AccountDeletionRequestResult.ConfirmationSent))
        verify(accountRequestRepository).deleteByUserIdAndType(user.id!!, AccountRequestType.ACCOUNT_DELETION)
        argumentCaptor<AccountRequest>().apply {
            verify(accountRequestRepository).persist(capture())
            assertThat(firstValue.id, notNullValue())
            assertThat(firstValue.userId, equalTo(user.id))
            assertThat(firstValue.type, equalTo(AccountRequestType.ACCOUNT_DELETION))
            assertThat(firstValue.tokenHash, equalTo(hashToken(token)))
            assertThat(firstValue.data, equalTo(emptyMap()))
        }
        verify(emailService).sendAccountDeletionConfirmationMail(
            user.username,
            user.email,
            fafProperties.account().accountDeletion().confirmationUrlFormat().format(token),
        )
    }

    @Test
    fun requestAccountDeletionReturnsUserNotFound() {
        val userId = 1
        whenever(userRepository.findById(userId)).thenReturn(null)

        val result = accountDeletionService.requestAccountDeletion(userId)

        assertThat(result, equalTo(AccountDeletionRequestResult.UserNotFound))
        verifyNoInteractions(accountRequestRepository)
        verifyNoInteractions(emailService)
        verifyNoInteractions(fafTokenService)
    }

    @Test
    fun validateAccountDeletionTokenReturnsValidUsername() {
        val user = buildTestUser()
        val token = "token"
        val pendingDeletion = buildPendingDeletion(user = user, token = token)
        whenever(fafTokenService.getTokenClaims(FafTokenType.ACCOUNT_DELETION, token)).thenReturn(
            mapOf(
                "deletionId" to pendingDeletion.id,
                "userId" to user.id.toString(),
            ),
        )
        whenever(accountRequestRepository.findById(pendingDeletion.id)).thenReturn(pendingDeletion)
        whenever(userRepository.findById(user.id!!)).thenReturn(user)

        val result = accountDeletionService.validateAccountDeletionToken(token)

        assertThat(result, equalTo(AccountDeletionValidationResult.Valid(user.username)))
    }

    @Test
    fun confirmAccountDeletionDeletesPendingDeletion() {
        val user = buildTestUser()
        val token = "token"
        val pendingDeletion = buildPendingDeletion(user = user, token = token)
        val event = AccountDeletedEvent(
            userId = user.id!!,
            username = user.username,
            email = user.email,
        )
        whenever(fafTokenService.getTokenClaims(FafTokenType.ACCOUNT_DELETION, token)).thenReturn(
            mapOf(
                "deletionId" to pendingDeletion.id,
                "userId" to user.id.toString(),
            ),
        )
        whenever(accountRequestRepository.findById(pendingDeletion.id)).thenReturn(pendingDeletion)
        whenever(userRepository.findById(user.id!!)).thenReturn(user)
        whenever(accountAnonymizationService.anonymizeUser(user.id!!, pendingDeletion)).thenReturn(event)

        val result = accountDeletionService.confirmAccountDeletion(token)

        assertThat(result, equalTo(AccountDeletionConfirmationResult.Confirmed))
        verify(accountAnonymizationService).anonymizeUser(user.id!!, pendingDeletion)
        verify(accountDeletionEventPublisher).publish(event)
    }

    @Test
    fun confirmAccountDeletionRejectsInvalidToken() {
        val token = "token"
        whenever(fafTokenService.getTokenClaims(FafTokenType.ACCOUNT_DELETION, token))
            .thenThrow(IllegalArgumentException())

        val result = accountDeletionService.confirmAccountDeletion(token)

        assertThat(result, equalTo(AccountDeletionConfirmationResult.InvalidToken))
        verifyNoInteractions(accountRequestRepository)
    }

    @Test
    fun confirmAccountDeletionReturnsPendingDeletionNotFound() {
        val user = buildTestUser()
        val token = "token"
        val deletionId = "deletion-id"
        whenever(fafTokenService.getTokenClaims(FafTokenType.ACCOUNT_DELETION, token)).thenReturn(
            mapOf(
                "deletionId" to deletionId,
                "userId" to user.id.toString(),
            ),
        )
        whenever(accountRequestRepository.findById(deletionId)).thenReturn(null)

        val result = accountDeletionService.confirmAccountDeletion(token)

        assertThat(result, equalTo(AccountDeletionConfirmationResult.PendingDeletionNotFound))
    }

    @Test
    fun confirmAccountDeletionRejectsWrongTokenHash() {
        val user = buildTestUser()
        val token = "token"
        val pendingDeletion = buildPendingDeletion(user = user, token = "different-token")
        whenever(fafTokenService.getTokenClaims(FafTokenType.ACCOUNT_DELETION, token)).thenReturn(
            mapOf(
                "deletionId" to pendingDeletion.id,
                "userId" to user.id.toString(),
            ),
        )
        whenever(accountRequestRepository.findById(pendingDeletion.id)).thenReturn(pendingDeletion)

        val result = accountDeletionService.confirmAccountDeletion(token)

        assertThat(result, equalTo(AccountDeletionConfirmationResult.InvalidToken))
    }

    @Test
    fun confirmAccountDeletionRejectsExpiredPendingDeletion() {
        val user = buildTestUser()
        val token = "token"
        val pendingDeletion = buildPendingDeletion(
            user = user,
            token = token,
            expiresAt = OffsetDateTime.now().minusMinutes(1),
        )
        whenever(fafTokenService.getTokenClaims(FafTokenType.ACCOUNT_DELETION, token)).thenReturn(
            mapOf(
                "deletionId" to pendingDeletion.id,
                "userId" to user.id.toString(),
            ),
        )
        whenever(accountRequestRepository.findById(pendingDeletion.id)).thenReturn(pendingDeletion)

        val result = accountDeletionService.confirmAccountDeletion(token)

        assertThat(result, equalTo(AccountDeletionConfirmationResult.InvalidToken))
    }

    @Test
    fun confirmAccountDeletionReturnsUserNotFoundIfUserNoLongerExists() {
        val user = buildTestUser()
        val token = "token"
        val pendingDeletion = buildPendingDeletion(user = user, token = token)
        whenever(fafTokenService.getTokenClaims(FafTokenType.ACCOUNT_DELETION, token)).thenReturn(
            mapOf(
                "deletionId" to pendingDeletion.id,
                "userId" to user.id.toString(),
            ),
        )
        whenever(accountRequestRepository.findById(pendingDeletion.id)).thenReturn(pendingDeletion)
        whenever(userRepository.findById(user.id!!)).thenReturn(null)

        val result = accountDeletionService.confirmAccountDeletion(token)

        assertThat(result, equalTo(AccountDeletionConfirmationResult.UserNotFound))
        verifyNoInteractions(accountAnonymizationService)
        verifyNoInteractions(accountDeletionEventPublisher)
    }

    private fun buildPendingDeletion(
        user: User,
        token: String,
        id: String = "deletion-id",
        expiresAt: OffsetDateTime = OffsetDateTime.now().plusHours(1),
    ) = AccountRequest(
        id = id,
        userId = user.id!!,
        type = AccountRequestType.ACCOUNT_DELETION,
        tokenHash = hashToken(token),
        expiresAt = expiresAt,
        data = emptyMap(),
    )

    private fun buildTestUser(
        id: Int = 1,
        username: String = "testUser",
        email: String = "old@example.com",
    ) = User(
        id = id,
        username = username,
        password = "password",
        email = email,
        ip = null,
        acceptedTos = null,
    )

    private fun hashToken(token: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))
}
