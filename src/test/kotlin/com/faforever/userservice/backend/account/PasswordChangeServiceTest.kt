package com.faforever.userservice.backend.account

import com.faforever.userservice.backend.domain.AccountRequest
import com.faforever.userservice.backend.domain.AccountRequestRepository
import com.faforever.userservice.backend.domain.AccountRequestType
import com.faforever.userservice.backend.domain.User
import com.faforever.userservice.backend.domain.UserRepository
import com.faforever.userservice.backend.email.EmailService
import com.faforever.userservice.backend.security.FafTokenService
import com.faforever.userservice.backend.security.FafTokenType
import com.faforever.userservice.backend.security.PasswordEncoder
import com.faforever.userservice.config.FafProperties
import io.quarkus.test.InjectMock
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
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
class PasswordChangeServiceTest {

    @Inject
    private lateinit var passwordChangeService: PasswordChangeService

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
    private lateinit var passwordEncoder: PasswordEncoder

    @Test
    fun requestPasswordChangeCreatesPendingChangeAndSendsConfirmation() {
        val user = buildTestUser()
        whenever(userRepository.findById(user.id!!)).thenReturn(user)
        whenever(
            fafTokenService.createToken(
                eq(FafTokenType.PASSWORD_CHANGE),
                any<TemporalAmount>(),
                any(),
            ),
        ).thenReturn("token")

        val result = passwordChangeService.requestPasswordChange(user.id!!)

        assertThat(result, equalTo(PasswordChangeRequestResult.ConfirmationSent))
        verify(accountRequestRepository).deleteByUserIdAndType(user.id!!, AccountRequestType.PASSWORD_CHANGE)
        argumentCaptor<AccountRequest>().apply {
            verify(accountRequestRepository).persist(capture())
            assertThat(firstValue.userId, equalTo(user.id))
            assertThat(firstValue.type, equalTo(AccountRequestType.PASSWORD_CHANGE))
            assertThat(firstValue.tokenHash, equalTo(hashToken("token")))
        }
        verify(emailService).sendPasswordChangeConfirmationMail(
            user.username,
            user.email,
            fafProperties.account().passwordChange().confirmationUrlFormat().format("token"),
        )
    }

    @Test
    fun setPasswordUpdatesPasswordAndDeletesPendingChange() {
        val user = buildTestUser()
        val token = "token"
        val newPassword = "newPassword123"
        val pendingChange = AccountRequest(
            id = "change-id",
            userId = user.id!!,
            type = AccountRequestType.PASSWORD_CHANGE,
            tokenHash = hashToken(token),
            expiresAt = OffsetDateTime.now().plusHours(1),
            data = emptyMap(),
        )
        whenever(fafTokenService.getTokenClaims(FafTokenType.PASSWORD_CHANGE, token)).thenReturn(
            mapOf(
                "changeId" to pendingChange.id,
                "userId" to user.id.toString(),
            ),
        )
        whenever(accountRequestRepository.findById(pendingChange.id)).thenReturn(pendingChange)
        whenever(userRepository.findById(user.id!!)).thenReturn(user)
        whenever(passwordEncoder.encode(newPassword)).thenReturn("encodedPassword")

        val result = passwordChangeService.setPassword(token, newPassword)

        assertThat(result, equalTo(PasswordChangeConfirmationResult.Confirmed))
        assertThat(user.password, equalTo("encodedPassword"))
        verify(accountRequestRepository).delete(pendingChange)
        verify(emailService).sendPasswordChangedNotificationMail(user.username, user.email)
    }

    @Test
    fun setPasswordRejectsInvalidToken() {
        val token = "token"
        whenever(fafTokenService.getTokenClaims(FafTokenType.PASSWORD_CHANGE, token)).thenThrow(IllegalArgumentException())

        val result = passwordChangeService.setPassword(token, "newPassword")

        assertThat(result, equalTo(PasswordChangeConfirmationResult.InvalidToken))
        verifyNoInteractions(accountRequestRepository)
    }

    @Test
    fun setPasswordReturnsPendingChangeNotFoundWhenNoPendingChange() {
        val user = buildTestUser()
        val token = "token"
        whenever(fafTokenService.getTokenClaims(FafTokenType.PASSWORD_CHANGE, token)).thenReturn(
            mapOf(
                "changeId" to "change-id",
                "userId" to user.id.toString(),
            ),
        )
        whenever(accountRequestRepository.findById("change-id")).thenReturn(null)

        val result = passwordChangeService.setPassword(token, "newPassword")

        assertThat(result, equalTo(PasswordChangeConfirmationResult.PendingChangeNotFound))
    }

    @Test
    fun passwordCheckReturnsTrueForCorrectPassword() {
        val user = buildTestUser()
        whenever(userRepository.findById(user.id!!)).thenReturn(user)
        whenever(passwordEncoder.matches("correctPassword", user.password)).thenReturn(true)

        val result = passwordChangeService.passwordCheck(user.id!!, "correctPassword")

        assertThat(result, equalTo(true))
    }

    @Test
    fun passwordCheckReturnsFalseForIncorrectPassword() {
        val user = buildTestUser()
        whenever(userRepository.findById(user.id!!)).thenReturn(user)
        whenever(passwordEncoder.matches("wrongPassword", user.password)).thenReturn(false)

        val result = passwordChangeService.passwordCheck(user.id!!, "wrongPassword")

        assertThat(result, equalTo(false))
    }

    private fun buildTestUser(
        id: Int = 1,
        username: String = "testUser",
        email: String = "test@example.com",
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
