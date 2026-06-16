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
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

sealed interface AccountDeletionRequestResult {
    data object ConfirmationSent : AccountDeletionRequestResult
    data object UserNotFound : AccountDeletionRequestResult
}

sealed interface AccountDeletionConfirmationResult {
    data object Confirmed : AccountDeletionConfirmationResult
    data object InvalidToken : AccountDeletionConfirmationResult
    data object PendingDeletionNotFound : AccountDeletionConfirmationResult
    data object UserNotFound : AccountDeletionConfirmationResult
    data object AnonymizationNotImplemented : AccountDeletionConfirmationResult
}

@ApplicationScoped
class AccountDeletionService(
    private val userRepository: UserRepository,
    private val accountRequestRepository: AccountRequestRepository,
    private val emailService: EmailService,
    private val fafTokenService: FafTokenService,
    private val fafProperties: FafProperties,
) {
    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(AccountDeletionService::class.java)
        private const val KEY_DELETION_ID = "deletionId"
        private const val KEY_USER_ID = "userId"
    }

    @Transactional
    fun requestAccountDeletion(userId: Int): AccountDeletionRequestResult {
        val user = userRepository.findById(userId) ?: return AccountDeletionRequestResult.UserNotFound

        createAccountDeletionRequest(user)

        return AccountDeletionRequestResult.ConfirmationSent
    }

    private fun createAccountDeletionRequest(user: User) {
        val userId = user.id ?: error("Cannot request account deletion for a user without an id")
        val deletionId = UUID.randomUUID().toString()
        val lifetime = Duration.ofSeconds(fafProperties.account().accountDeletion().linkExpirationSeconds())
        val expiresAt = OffsetDateTime.now().plus(lifetime)

        val token = fafTokenService.createToken(
            FafTokenType.ACCOUNT_DELETION,
            lifetime,
            mapOf(
                KEY_DELETION_ID to deletionId,
                KEY_USER_ID to userId.toString(),
            ),
        )

        accountRequestRepository.deleteByUserIdAndType(userId, AccountRequestType.ACCOUNT_DELETION)
        accountRequestRepository.persist(
            AccountRequest(
                id = deletionId,
                userId = userId,
                type = AccountRequestType.ACCOUNT_DELETION,
                tokenHash = hashToken(token),
                expiresAt = expiresAt,
                data = emptyMap(),
            ),
        )

        val confirmationUrl = fafProperties.account().accountDeletion().confirmationUrlFormat().format(token)
        emailService.sendAccountDeletionConfirmationMail(user.username, user.email, confirmationUrl)
    }

    @Transactional
    fun confirmAccountDeletion(token: String): AccountDeletionConfirmationResult {
        // TODO: Implement when working on the confirmation part.
        //
        // Expected flow:
        // 1. Parse token using fafTokenService.getTokenClaims(FafTokenType.ACCOUNT_DELETION, token)
        // 2. Read deletionId and userId from token claims
        // 3. Find AccountRequest by deletionId
        // 4. Validate:
        //    - request type is ACCOUNT_DELETION
        //    - userId matches
        //    - token hash matches
        //    - request is not expired
        // 5. Show final confirmation page
        // 6. If user confirms again, call anonymizeUser(userId)
        // 7. Delete the account_request row
        return AccountDeletionConfirmationResult.AnonymizationNotImplemented
    }

    private fun anonymizeUser(userId: Int) {
        // TODO: Implement later using the FAF-provided anonymisation script/process.
        // NOTE: Project sheet says to ask for Python script in Zulip chat.

        LOG.warn("Account anonymization is not implemented yet for user id {}", userId)
    }

    private fun hashToken(token: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))
}
