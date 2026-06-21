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
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

sealed interface PasswordChangeRequestResult {
    data object ConfirmationSent : PasswordChangeRequestResult
    data object UserNotFound : PasswordChangeRequestResult
}

sealed interface PasswordChangeConfirmationResult {
    data object Confirmed : PasswordChangeConfirmationResult
    data object InvalidToken : PasswordChangeConfirmationResult
    data object PendingChangeNotFound : PasswordChangeConfirmationResult
    data object UserNotFound : PasswordChangeConfirmationResult
    data object PasswordUnchanged : PasswordChangeConfirmationResult
}

@ApplicationScoped
class PasswordChangeService(
    private val userRepository: UserRepository,
    private val accountRequestRepository: AccountRequestRepository,
    private val emailService: EmailService,
    private val fafTokenService: FafTokenService,
    private val fafProperties: FafProperties,
    private val passwordEncoder: PasswordEncoder,
) {
    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(PasswordChangeService::class.java)
        private const val KEY_CHANGE_ID = "changeId"
        private const val KEY_USER_ID = "userId"
    }

    @Transactional
    fun requestPasswordChange(userId: Int): PasswordChangeRequestResult {
        val user = userRepository.findById(userId) ?: return PasswordChangeRequestResult.UserNotFound

        createPasswordChangeRequest(user)
        return PasswordChangeRequestResult.ConfirmationSent
    }

    private fun createPasswordChangeRequest(user: User) {
        val userId = user.id ?: error("Cannot change password for a user without an id")
        val changeId = UUID.randomUUID().toString()
        val lifetime = Duration.ofSeconds(fafProperties.account().passwordChange().linkExpirationSeconds())
        val expiresAt = OffsetDateTime.now().plus(lifetime)
        val token = fafTokenService.createToken(
            FafTokenType.PASSWORD_CHANGE,
            lifetime,
            mapOf(
                KEY_CHANGE_ID to changeId,
                KEY_USER_ID to userId.toString(),
            ),
        )

        accountRequestRepository.deleteByUserIdAndType(userId, AccountRequestType.PASSWORD_CHANGE)
        accountRequestRepository.persist(
            AccountRequest(
                id = changeId,
                userId = userId,
                type = AccountRequestType.PASSWORD_CHANGE,
                tokenHash = hashToken(token),
                expiresAt = expiresAt,
                data = emptyMap(),
            ),
        )

        val confirmationUrl =
            fafProperties.account().passwordChange().confirmationUrlFormat().format(token)
        emailService.sendPasswordChangeConfirmationMail(user.username, user.email, confirmationUrl)
    }

    @Transactional
    fun setPassword(token: String, newPassword: String): PasswordChangeConfirmationResult {
        val claims = try {
            fafTokenService.getTokenClaims(FafTokenType.PASSWORD_CHANGE, token)
        } catch (exception: Exception) {
            LOG.info("Unable to extract password change token claims", exception)
            return PasswordChangeConfirmationResult.InvalidToken
        }

        val changeId = claims[KEY_CHANGE_ID]
        val userId = claims[KEY_USER_ID]?.toIntOrNull()
        if (changeId.isNullOrBlank() || userId == null) {
            return PasswordChangeConfirmationResult.InvalidToken
        }

        val pendingChange = accountRequestRepository.findById(changeId)
            ?: return PasswordChangeConfirmationResult.PendingChangeNotFound
        if (
            pendingChange.userId != userId ||
            pendingChange.type != AccountRequestType.PASSWORD_CHANGE ||
            pendingChange.tokenHash != hashToken(token)
        ) {
            return PasswordChangeConfirmationResult.InvalidToken
        }

        val user = userRepository.findById(userId)
            ?: return PasswordChangeConfirmationResult.UserNotFound.also {
                accountRequestRepository.delete(pendingChange)
            }

        if (passwordEncoder.matches(newPassword, user.password)) {
            return PasswordChangeConfirmationResult.PasswordUnchanged
        }

        user.password = passwordEncoder.encode(newPassword)
        accountRequestRepository.delete(pendingChange)
        emailService.sendPasswordChangedNotificationMail(user.username, user.email)

        return PasswordChangeConfirmationResult.Confirmed
    }

    fun passwordCheck(userID: Int, currentEnteredPassword: String): Boolean {
        val user = userRepository.findById(userID) ?: return false
        return passwordEncoder.matches(currentEnteredPassword, user.password)
    }

    private fun hashToken(token: String): String =
        Base64.getEncoder()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))
}
