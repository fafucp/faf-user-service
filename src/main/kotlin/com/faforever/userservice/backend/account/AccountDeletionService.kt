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
import java.time.Duration
import java.time.OffsetDateTime

sealed interface AccountDeletionRequestResult {
    data object ConfirmationSent : AccountDeletionRequestResult
    data object UserNotFound : AccountDeletionRequestResult
}

sealed interface AccountDeletionValidationResult {
    data class Valid(val username: String) : AccountDeletionValidationResult
    data object InvalidToken : AccountDeletionValidationResult
    data object PendingDeletionNotFound : AccountDeletionValidationResult
    data object UserNotFound : AccountDeletionValidationResult
}

sealed interface AccountDeletionConfirmationResult {
    data object Confirmed : AccountDeletionConfirmationResult
    data object InvalidToken : AccountDeletionConfirmationResult
    data object PendingDeletionNotFound : AccountDeletionConfirmationResult
    data object UserNotFound : AccountDeletionConfirmationResult
    data object AnonymizationFailed : AccountDeletionConfirmationResult
}

@ApplicationScoped
class AccountDeletionService(
    private val userRepository: UserRepository,
    private val accountRequestRepository: AccountRequestRepository,
    private val emailService: EmailService,
    private val fafTokenService: FafTokenService,
    private val fafProperties: FafProperties,
    private val accountAnonymizationService: AccountAnonymizationService,
    private val accountDeletionEventPublisher: AccountDeletionEventPublisher,
) {
    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(AccountDeletionService::class.java)
        private const val KEY_USER_ID = "userId"
    }

    @Transactional
    fun requestAccountDeletion(userId: Int): AccountDeletionRequestResult {
        LOG.info("Account deletion requested for user id {}", userId)

        val user = userRepository.findById(userId)
            ?: return AccountDeletionRequestResult.UserNotFound.also {
                LOG.warn("Account deletion request rejected because user id {} was not found", userId)
            }

        createAccountDeletionRequest(user)
        LOG.info("Account deletion confirmation request created for user id {}", userId)

        return AccountDeletionRequestResult.ConfirmationSent
    }

    private fun createAccountDeletionRequest(user: User) {
        val userId = user.id ?: error("Cannot request account deletion for a user without an id")
        val lifetime = Duration.ofSeconds(fafProperties.account().accountDeletion().linkExpirationSeconds())

        val token = fafTokenService.createToken(
            FafTokenType.ACCOUNT_DELETION,
            lifetime,
            mapOf(KEY_USER_ID to userId.toString()),
        )

        val confirmationUrl = fafProperties.account().accountDeletion().confirmationUrlFormat().format(token)
        emailService.sendAccountDeletionConfirmationMail(user.username, user.email, confirmationUrl)
        LOG.info("Account deletion confirmation email queued for user id {}", userId)
    }

    @Transactional
    fun validateAccountDeletionToken(token: String): AccountDeletionValidationResult {
        return when (val lookup = findValidPendingDeletion(token)) {
            AccountDeletionLookup.InvalidToken -> AccountDeletionValidationResult.InvalidToken
            AccountDeletionLookup.PendingDeletionNotFound -> AccountDeletionValidationResult.PendingDeletionNotFound
            AccountDeletionLookup.UserNotFound -> AccountDeletionValidationResult.UserNotFound
            is AccountDeletionLookup.Valid -> AccountDeletionValidationResult.Valid(lookup.user.username)
        }
    }

    @Transactional
    fun confirmAccountDeletion(token: String): AccountDeletionConfirmationResult {
        LOG.info("Account deletion confirmation received")

        val claims = try {
            fafTokenService.consumeToken(FafTokenType.ACCOUNT_DELETION, token)
        } catch (exception: Exception) {
            LOG.info("Unable to consume account deletion token", exception)
            return AccountDeletionConfirmationResult.InvalidToken
        }

        val userId = claims[KEY_USER_ID]?.toIntOrNull()
            ?: return AccountDeletionConfirmationResult.InvalidToken

        val user = userRepository.findById(userId)
            ?: return AccountDeletionConfirmationResult.UserNotFound

        try {
            LOG.info("Confirming account deletion for user id {}", user.id)

            val event = accountAnonymizationService.anonymizeUser(userId)
            LOG.info(
                "Local account anonymization completed for user id {}; publishing account deletion event",
                userId,
            )

            accountDeletionEventPublisher.publish(event)
            return AccountDeletionConfirmationResult.Confirmed
        } catch (exception: Exception) {
            LOG.error("Failed to anonymize account for user id {}", userId, exception)
            return AccountDeletionConfirmationResult.AnonymizationFailed
        }
    }

    private fun findValidPendingDeletion(token: String): AccountDeletionLookup {
        val pendingDeletion = accountRequestRepository.findById(token)
            ?: return AccountDeletionLookup.PendingDeletionNotFound

        if (pendingDeletion.type != AccountRequestType.ACCOUNT_DELETION) {
            return AccountDeletionLookup.InvalidToken
        }

        if (pendingDeletion.expiresAt.isBefore(OffsetDateTime.now())) {
            return AccountDeletionLookup.InvalidToken
        }

        val userId = pendingDeletion.userId
            ?: return AccountDeletionLookup.InvalidToken

        val user = userRepository.findById(userId)
            ?: return AccountDeletionLookup.UserNotFound

        return AccountDeletionLookup.Valid(pendingDeletion, user)
    }

    private sealed interface AccountDeletionLookup {
        data class Valid(val pendingDeletion: AccountRequest, val user: User) : AccountDeletionLookup
        data object InvalidToken : AccountDeletionLookup
        data object PendingDeletionNotFound : AccountDeletionLookup
        data object UserNotFound : AccountDeletionLookup
    }
}
