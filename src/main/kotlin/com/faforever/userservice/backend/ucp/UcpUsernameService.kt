package com.faforever.userservice.backend.ucp

import com.faforever.userservice.backend.account.RegistrationService
import com.faforever.userservice.backend.account.UsernameStatus
import com.faforever.userservice.backend.domain.UserRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

@ApplicationScoped
class UcpUsernameService(
    private val ucpSessionService: UcpSessionService,
    private val userRepository: UserRepository,
    private val registrationService: RegistrationService,
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

        // Validate new username
        if (trimmedUsername.isEmpty()) {
            return UsernameChangeResult.ValidationError("Username cannot be empty")
        }

        if (!trimmedUsername[0].isLetter()) {
            return UsernameChangeResult.ValidationError("Username must start with a letter")
        }

        if (trimmedUsername.length !in 3..15) {
            return UsernameChangeResult.ValidationError("Username must be between 3 and 15 characters")
        }

        if (Regex("[^A-Za-z0-9_-]").containsMatchIn(trimmedUsername)) {
            return UsernameChangeResult.ValidationError("Username can only contain letters, numbers, underscores, and dashes")
        }

        if (trimmedUsername == currentUser.userName) {
            return UsernameChangeResult.ValidationError("New username must be different from current username")
        }

        when (registrationService.usernameAvailable(trimmedUsername)) {
            UsernameStatus.USERNAME_TAKEN -> {
                return UsernameChangeResult.ValidationError("Username is already taken")
            }
            UsernameStatus.USERNAME_RESERVED -> {
                return UsernameChangeResult.ValidationError("Username is reserved")
            }
            UsernameStatus.USERNAME_AVAILABLE -> {
                // allowed, continue
            }
        }

        try {
            userRepository.updateUsername(currentUser.userId, trimmedUsername)
            ucpSessionService.setCurrentUser(UcpUser(currentUser.userId, trimmedUsername))
            return UsernameChangeResult.Success(currentUser.userId, trimmedUsername)
        } catch (e: Exception) {
            return UsernameChangeResult.ValidationError("Unable to change username. Please try again later.")
        }
    }
}
