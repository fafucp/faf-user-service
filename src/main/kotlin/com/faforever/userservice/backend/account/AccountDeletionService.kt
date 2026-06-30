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
        private const val KEY_DELETION_ID = "deletionId"
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
        LOG.info(
            "Account deletion confirmation email queued for user id {} with deletion request id {}",
            userId,
            deletionId,
        )
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

    fun confirmAccountDeletion(token: String): AccountDeletionConfirmationResult {
        LOG.info("Account deletion confirmation received")

        return when (val lookup = findValidPendingDeletion(token)) {
            AccountDeletionLookup.InvalidToken -> {
                LOG.warn("Account deletion confirmation rejected because token is invalid")
                AccountDeletionConfirmationResult.InvalidToken
            }

            AccountDeletionLookup.PendingDeletionNotFound -> {
                LOG.warn("Account deletion confirmation rejected because pending deletion was not found")
                AccountDeletionConfirmationResult.PendingDeletionNotFound
            }

            AccountDeletionLookup.UserNotFound -> {
                LOG.warn("Account deletion confirmation rejected because user was not found")
                AccountDeletionConfirmationResult.UserNotFound
            }
            is AccountDeletionLookup.Valid -> {
                val userId = lookup.user.id ?: return AccountDeletionConfirmationResult.UserNotFound

                try {
                    LOG.info(
                        "Confirming account deletion for user id {} with deletion request id {}",
                        userId,
                        lookup.pendingDeletion.id,
                    )

                    val event = accountAnonymizationService.anonymizeUser(userId, lookup.pendingDeletion)
                    LOG.info(
                        "Local account anonymization completed for user id {}; publishing account deletion event",
                        userId,
                    )

                    accountDeletionEventPublisher.publish(event)
                    AccountDeletionConfirmationResult.Confirmed
                } catch (exception: Exception) {
                    LOG.error("Failed to anonymize account for user id {}", userId, exception)
                    AccountDeletionConfirmationResult.AnonymizationFailed
                }
            }
        }
    }

    private fun findValidPendingDeletion(token: String): AccountDeletionLookup {
        val claims = try {
            fafTokenService.getTokenClaims(FafTokenType.ACCOUNT_DELETION, token)
        } catch (exception: Exception) {
            LOG.info("Unable to extract account deletion token claims", exception)
            return AccountDeletionLookup.InvalidToken
        }

        val deletionId = claims[KEY_DELETION_ID]
        val userId = claims[KEY_USER_ID]?.toIntOrNull()
        if (deletionId.isNullOrBlank() || userId == null) {
            return AccountDeletionLookup.InvalidToken
        }

        val pendingDeletion = accountRequestRepository.findById(deletionId)
            ?: return AccountDeletionLookup.PendingDeletionNotFound

        if (
            pendingDeletion.userId != userId ||
            pendingDeletion.type != AccountRequestType.ACCOUNT_DELETION ||
            pendingDeletion.tokenHash != hashToken(token) ||
            pendingDeletion.expiresAt <= OffsetDateTime.now()
        ) {
            return AccountDeletionLookup.InvalidToken
        }

        val user = userRepository.findById(userId)
            ?: return AccountDeletionLookup.UserNotFound

        return AccountDeletionLookup.Valid(pendingDeletion, user)
    }

    private fun hashToken(token: String): String =
        Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray()),
        )

    private sealed interface AccountDeletionLookup {
        data class Valid(val pendingDeletion: AccountRequest, val user: User) : AccountDeletionLookup
        data object InvalidToken : AccountDeletionLookup
        data object PendingDeletionNotFound : AccountDeletionLookup
        data object UserNotFound : AccountDeletionLookup
    }
}
