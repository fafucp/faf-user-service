package com.faforever.userservice.backend.ucp

import com.faforever.userservice.backend.account.RegistrationService
import com.faforever.userservice.backend.account.UsernameStatus
import com.faforever.userservice.backend.account.UsernameValidator
import com.faforever.userservice.backend.domain.NameRecord
import com.faforever.userservice.backend.domain.NameRecordRepository
import com.faforever.userservice.backend.domain.UserRepository
import com.faforever.userservice.config.FafProperties
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.OffsetDateTime

@ApplicationScoped
class UcpUsernameService(
    private val ucpSessionService: UcpSessionService,
    private val userRepository: UserRepository,
    private val nameRecordRepository: NameRecordRepository,
    private val registrationService: RegistrationService,
    private val fafProperties: FafProperties,
) {
    sealed interface UsernameChangeResult {
        data class Success(val userId: Int, val newUsername: String) : UsernameChangeResult
        data class ValidationError(val message: String) : UsernameChangeResult
        data object NotLoggedIn : UsernameChangeResult
    }

    @Transactional
    fun changeUsername(newUsername: String): UsernameChangeResult {
        val currentUser = ucpSessionService.getCurrentUser()
            ?: return UsernameChangeResult.NotLoggedIn

        val trimmedUsername = newUsername.trim()
        val now = OffsetDateTime.now()

        // Validate new username
        if (trimmedUsername.isEmpty()) {
            return UsernameChangeResult.ValidationError("ucp.username.error.empty")
        }

        if (!UsernameValidator.startsWithLetter(trimmedUsername)) {
            return UsernameChangeResult.ValidationError("ucp.username.error.mustStartWithLetter")
        }

        if (!UsernameValidator.hasValidLength(trimmedUsername)) {
            return UsernameChangeResult.ValidationError("ucp.username.error.length")
        }

        if (!UsernameValidator.containsOnlyAllowedCharacters(trimmedUsername)) {
            return UsernameChangeResult.ValidationError("ucp.username.error.invalidCharacters")
        }

        if (trimmedUsername == currentUser.userName) {
            return UsernameChangeResult.ValidationError("ucp.username.error.sameAsCurrent")
        }

        // Check username-change cooldown
        if (fafProperties.account().username().minimumDaysBetweenUsernameChange() > 0) {
            val cooldownStart = now.minusDays(
                fafProperties.account().username().minimumDaysBetweenUsernameChange().toLong(),
            )
            if (nameRecordRepository.existsByUserIdAndChangeTimeAfter(currentUser.userId, cooldownStart)) {
                return UsernameChangeResult.ValidationError("ucp.username.error.cooldown")
            }
        }

        when (registrationService.usernameAvailable(trimmedUsername)) {
            UsernameStatus.USERNAME_TAKEN -> {
                return UsernameChangeResult.ValidationError("ucp.username.error.taken")
            }
            UsernameStatus.USERNAME_RESERVED -> {
                return UsernameChangeResult.ValidationError("ucp.username.error.reserved")
            }
            UsernameStatus.USERNAME_AVAILABLE -> {
                // allowed, continue
            }
        }

        val previousUsername = currentUser.userName
        try {
            userRepository.updateUsername(currentUser.userId, trimmedUsername)
            nameRecordRepository.persist(
                NameRecord(
                    userId = currentUser.userId,
                    changeTime = now,
                    previousName = previousUsername,
                ),
            )
            ucpSessionService.setCurrentUser(UcpUser(currentUser.userId, trimmedUsername))
        } catch (exception: Exception) {
            return UsernameChangeResult.ValidationError("ucp.username.error.updateFailed")
        }

        return UsernameChangeResult.Success(currentUser.userId, trimmedUsername)
    }
}
