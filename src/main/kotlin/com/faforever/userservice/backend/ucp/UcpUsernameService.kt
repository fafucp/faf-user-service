package com.faforever.userservice.backend.ucp

import com.faforever.userservice.backend.domain.UserRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@ApplicationScoped
class UcpUsernameService(
    private val ucpSessionService: UcpSessionService,
    private val userRepository: UserRepository,
) {
    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(UcpUsernameService::class.java)
    }

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

        if (trimmedUsername.length < 3) {
            return UsernameChangeResult.ValidationError("Username must be at least 3 characters long")
        }

        if (trimmedUsername.length > 20) {
            return UsernameChangeResult.ValidationError("Username must not exceed 20 characters")
        }

        if (trimmedUsername == currentUser.userName) {
            return UsernameChangeResult.ValidationError("New username must be different from current username")
        }

        if (userRepository.existsByUsername(trimmedUsername)) {
            LOG.warn("Username already taken: {} for userId={}", trimmedUsername, currentUser.userId)
            return UsernameChangeResult.ValidationError("Username is already taken")
        }

        try {
            LOG.info("Updating username: userId={}, oldUsername={}, newUsername={}",
                currentUser.userId, currentUser.userName, trimmedUsername)

            userRepository.updateUsername(currentUser.userId, trimmedUsername)
            ucpSessionService.setCurrentUser(UcpUser(currentUser.userId, trimmedUsername))
            return UsernameChangeResult.Success(currentUser.userId, trimmedUsername)
        } catch (e: Exception) {
            LOG.error("Failed to change username for userId={}: {}", currentUser.userId, e.message, e)
            return UsernameChangeResult.ValidationError("Unable to change username. Please try again later.")
        }
    }
}
