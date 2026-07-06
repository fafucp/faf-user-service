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
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.OffsetDateTime
import java.time.temporal.TemporalAmount

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
    fun requestAccountDeletionCreatesTokenAndSendsConfirmationEmail() {
        val user = buildTestUser()
        val token = "token"
        whenever(userRepository.findById(user.id!!)).thenReturn(user)
        whenever(
            fafTokenService.createToken(
                eq(FafTokenType.ACCOUNT_DELETION),
                any<TemporalAmount>(),
                eq(mapOf("userId" to user.id.toString())),
            ),
        ).thenReturn(token)

        val result = accountDeletionService.requestAccountDeletion(user.id!!)

        assertThat(result, equalTo(AccountDeletionRequestResult.ConfirmationSent))
        verify(fafTokenService).createToken(
            eq(FafTokenType.ACCOUNT_DELETION),
            any<TemporalAmount>(),
            eq(mapOf("userId" to user.id.toString())),
        )
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
        val pendingDeletion = buildPendingDeletion(user = user, id = token)
        whenever(accountRequestRepository.findById(token)).thenReturn(pendingDeletion)
        whenever(userRepository.findById(user.id!!)).thenReturn(user)

        val result = accountDeletionService.validateAccountDeletionToken(token)

        assertThat(result, equalTo(AccountDeletionValidationResult.Valid(user.username)))
        verifyNoInteractions(fafTokenService)
        verifyNoInteractions(accountAnonymizationService)
        verifyNoInteractions(accountDeletionEventPublisher)
    }

    @Test
    fun validateAccountDeletionTokenReturnsPendingDeletionNotFound() {
        val token = "token"
        whenever(accountRequestRepository.findById(token)).thenReturn(null)

        val result = accountDeletionService.validateAccountDeletionToken(token)

        assertThat(result, equalTo(AccountDeletionValidationResult.PendingDeletionNotFound))
        verifyNoInteractions(fafTokenService)
        verifyNoInteractions(accountAnonymizationService)
        verifyNoInteractions(accountDeletionEventPublisher)
    }

    @Test
    fun validateAccountDeletionTokenRejectsWrongRequestType() {
        val user = buildTestUser()
        val token = "token"
        val pendingDeletion = buildPendingDeletion(
            user = user,
            id = token,
            type = AccountRequestType.EMAIL_CHANGE,
        )
        whenever(accountRequestRepository.findById(token)).thenReturn(pendingDeletion)

        val result = accountDeletionService.validateAccountDeletionToken(token)

        assertThat(result, equalTo(AccountDeletionValidationResult.InvalidToken))
        verifyNoInteractions(fafTokenService)
        verifyNoInteractions(accountAnonymizationService)
        verifyNoInteractions(accountDeletionEventPublisher)
    }

    @Test
    fun validateAccountDeletionTokenRejectsExpiredPendingDeletion() {
        val user = buildTestUser()
        val token = "token"
        val pendingDeletion = buildPendingDeletion(
            user = user,
            id = token,
            expiresAt = OffsetDateTime.now().minusMinutes(1),
        )
        whenever(accountRequestRepository.findById(token)).thenReturn(pendingDeletion)

        val result = accountDeletionService.validateAccountDeletionToken(token)

        assertThat(result, equalTo(AccountDeletionValidationResult.InvalidToken))
        verifyNoInteractions(fafTokenService)
        verifyNoInteractions(accountAnonymizationService)
        verifyNoInteractions(accountDeletionEventPublisher)
    }

    @Test
    fun validateAccountDeletionTokenReturnsUserNotFoundIfUserNoLongerExists() {
        val user = buildTestUser()
        val token = "token"
        val pendingDeletion = buildPendingDeletion(user = user, id = token)
        whenever(accountRequestRepository.findById(token)).thenReturn(pendingDeletion)
        whenever(userRepository.findById(user.id!!)).thenReturn(null)

        val result = accountDeletionService.validateAccountDeletionToken(token)

        assertThat(result, equalTo(AccountDeletionValidationResult.UserNotFound))
        verifyNoInteractions(fafTokenService)
        verifyNoInteractions(accountAnonymizationService)
        verifyNoInteractions(accountDeletionEventPublisher)
    }

    @Test
    fun confirmAccountDeletionConsumesTokenAnonymizesAccountAndPublishesEvent() {
        val user = buildTestUser()
        val token = "token"
        val event = AccountDeletedEvent(
            userId = user.id!!,
            username = user.username,
            email = user.email,
        )
        whenever(fafTokenService.consumeToken(FafTokenType.ACCOUNT_DELETION, token))
            .thenReturn(mapOf("userId" to user.id.toString()))
        whenever(userRepository.findById(user.id!!)).thenReturn(user)
        whenever(accountAnonymizationService.anonymizeUser(user.id!!)).thenReturn(event)

        val result = accountDeletionService.confirmAccountDeletion(token)

        assertThat(result, equalTo(AccountDeletionConfirmationResult.Confirmed))
        verify(fafTokenService).consumeToken(FafTokenType.ACCOUNT_DELETION, token)
        verify(accountAnonymizationService).anonymizeUser(user.id!!)
        verify(accountDeletionEventPublisher).publish(event)
    }

    @Test
    fun confirmAccountDeletionRejectsInvalidTokenWhenTokenConsumptionFails() {
        val token = "token"
        whenever(fafTokenService.consumeToken(FafTokenType.ACCOUNT_DELETION, token))
            .thenThrow(IllegalArgumentException("Token not found"))

        val result = accountDeletionService.confirmAccountDeletion(token)

        assertThat(result, equalTo(AccountDeletionConfirmationResult.InvalidToken))
        verify(fafTokenService).consumeToken(FafTokenType.ACCOUNT_DELETION, token)
        verifyNoInteractions(accountAnonymizationService)
        verifyNoInteractions(accountDeletionEventPublisher)
    }

    @Test
    fun confirmAccountDeletionRejectsTokenWithoutValidUserId() {
        val token = "token"
        whenever(fafTokenService.consumeToken(FafTokenType.ACCOUNT_DELETION, token))
            .thenReturn(emptyMap())

        val result = accountDeletionService.confirmAccountDeletion(token)

        assertThat(result, equalTo(AccountDeletionConfirmationResult.InvalidToken))
        verify(fafTokenService).consumeToken(FafTokenType.ACCOUNT_DELETION, token)
        verifyNoInteractions(accountAnonymizationService)
        verifyNoInteractions(accountDeletionEventPublisher)
    }

    @Test
    fun confirmAccountDeletionReturnsUserNotFoundIfUserNoLongerExists() {
        val user = buildTestUser()
        val token = "token"
        whenever(fafTokenService.consumeToken(FafTokenType.ACCOUNT_DELETION, token))
            .thenReturn(mapOf("userId" to user.id.toString()))
        whenever(userRepository.findById(user.id!!)).thenReturn(null)

        val result = accountDeletionService.confirmAccountDeletion(token)

        assertThat(result, equalTo(AccountDeletionConfirmationResult.UserNotFound))
        verify(fafTokenService).consumeToken(FafTokenType.ACCOUNT_DELETION, token)
        verifyNoInteractions(accountAnonymizationService)
        verifyNoInteractions(accountDeletionEventPublisher)
    }

    @Test
    fun confirmAccountDeletionReturnsAnonymizationFailedWhenAnonymizationFails() {
        val user = buildTestUser()
        val token = "token"
        whenever(fafTokenService.consumeToken(FafTokenType.ACCOUNT_DELETION, token))
            .thenReturn(mapOf("userId" to user.id.toString()))
        whenever(userRepository.findById(user.id!!)).thenReturn(user)
        whenever(accountAnonymizationService.anonymizeUser(user.id!!))
            .thenThrow(RuntimeException("anonymization failed"))

        val result = accountDeletionService.confirmAccountDeletion(token)

        assertThat(result, equalTo(AccountDeletionConfirmationResult.AnonymizationFailed))
        verify(fafTokenService).consumeToken(FafTokenType.ACCOUNT_DELETION, token)
        verify(accountAnonymizationService).anonymizeUser(user.id!!)
        verifyNoInteractions(accountDeletionEventPublisher)
    }

    private fun buildPendingDeletion(
        user: User,
        id: String = "deletion-id",
        type: AccountRequestType = AccountRequestType.ACCOUNT_DELETION,
        expiresAt: OffsetDateTime = OffsetDateTime.now().plusHours(1),
    ) = AccountRequest(
        id = id,
        userId = user.id!!,
        type = type,
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
}
