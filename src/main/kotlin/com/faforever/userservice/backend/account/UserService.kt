package com.faforever.userservice.backend.account

import com.faforever.userservice.backend.domain.NameRecord
import com.faforever.userservice.backend.domain.NameRecordRepository
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
import java.time.Duration
import java.time.OffsetDateTime

sealed interface ChangePasswordResult {
    data object Success : ChangePasswordResult
    data object InvalidCurrentPassword : ChangePasswordResult
}

sealed interface ChangeUsernameResult {
    data object Success : ChangeUsernameResult
    data object UsernameTaken : ChangeUsernameResult
    data object UsernameReserved : ChangeUsernameResult
    data object TooEarly : ChangeUsernameResult
}

sealed interface ChangeEmailResult {
    data object EmailSent : ChangeEmailResult
    data object EmailBlacklisted : ChangeEmailResult
    data object EmailTaken : ChangeEmailResult
}

@ApplicationScoped
class UserService(
    private val userRepository: UserRepository,
    private val nameRecordRepository: NameRecordRepository,
    private val passwordEncoder: PasswordEncoder,
    private val fafProperties: FafProperties,
    private val fafTokenService: FafTokenService,
    private val emailService: EmailService,
) {
    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(UserService::class.java)
        private const val KEY_USER_ID = "id"
        private const val KEY_NEW_EMAIL = "newEmail"
    }

    @Transactional
    fun changePassword(user: User, currentPassword: String, newPassword: String): ChangePasswordResult {
        if (!passwordEncoder.matches(currentPassword, user.password)) {
            LOG.debug("Password change failed for user '{}': current password does not match", user.username)
            return ChangePasswordResult.InvalidCurrentPassword
        }

        user.password = passwordEncoder.encode(newPassword)
        userRepository.persist(user)

        LOG.info("Password changed for user '{}'", user.username)
        return ChangePasswordResult.Success
    }

    @Transactional
    fun changeUsername(user: User, newUsername: String): ChangeUsernameResult {
        val minimumDays = fafProperties.account().username().minimumDaysBetweenUsernameChange()
        val reservationMonths = fafProperties.account().username().usernameReservationTimeInMonths()

        val hasRecentChange = nameRecordRepository.existsByUserIdAndChangeTimeAfter(
            user.id!!,
            OffsetDateTime.now().minusDays(minimumDays.toLong()),
        )

        if (hasRecentChange) {
            LOG.debug("Username change denied for user '{}': too early since last change", user.username)
            return ChangeUsernameResult.TooEarly
        }

        if (userRepository.existsByUsername(newUsername)) {
            LOG.debug("Username change denied for user '{}': username '{}' is taken", user.username, newUsername)
            return ChangeUsernameResult.UsernameTaken
        }

        val reservedByOther = nameRecordRepository.existsByPreviousNameAndChangeTimeAfterAndUserIdNotEquals(
            newUsername,
            OffsetDateTime.now().minusMonths(reservationMonths),
            user.id!!,
        )

        if (reservedByOther) {
            LOG.debug("Username change denied for user '{}': username '{}' is reserved", user.username, newUsername)
            return ChangeUsernameResult.UsernameReserved
        }

        val previousName = user.username
        val nameRecord = NameRecord(0, user.id!!, OffsetDateTime.now(), previousName)
        nameRecordRepository.persist(nameRecord)

        user.username = newUsername
        userRepository.persist(user)

        LOG.info("Username changed for user '{}' from '{}' to '{}'", user.id, previousName, newUsername)
        return ChangeUsernameResult.Success
    }

    fun requestEmailChange(user: User, newEmail: String): ChangeEmailResult {
        val validationResult = emailService.validateEmailAddress(newEmail)
        if (validationResult == EmailService.ValidationResult.BLACKLISTED) {
            return ChangeEmailResult.EmailBlacklisted
        }

        if (userRepository.existsByEmail(newEmail)) {
            return ChangeEmailResult.EmailTaken
        }

        val token = fafTokenService.createToken(
            fafTokenType = FafTokenType.EMAIL_CHANGE,
            lifetime = Duration.ofSeconds(fafProperties.account().emailChange().linkExpirationSeconds()),
            attributes = mapOf(
                KEY_USER_ID to user.id.toString(),
                KEY_NEW_EMAIL to newEmail,
            ),
        )

        val confirmationUrl = fafProperties.account().emailChange().emailChangeUrlFormat().format(token)
        emailService.sendEmailChangeMail(user.username, newEmail, confirmationUrl)

        LOG.info("Email change confirmation sent for user '{}' to '{}'", user.username, newEmail)
        return ChangeEmailResult.EmailSent
    }

    @Transactional
    fun confirmEmailChange(token: String): Boolean {
        val claims = try {
            fafTokenService.getTokenClaims(FafTokenType.EMAIL_CHANGE, token)
        } catch (exception: Exception) {
            LOG.error("Unable to extract claims from email change token", exception)
            return false
        }

        val userId = claims[KEY_USER_ID]
        val newEmail = claims[KEY_NEW_EMAIL]

        if (userId.isNullOrBlank() || newEmail.isNullOrBlank()) {
            LOG.error("Email change token missing required claims")
            return false
        }

        val user = userRepository.findById(userId.toInt())
        if (user == null) {
            LOG.error("User with id {} not found for email change", userId)
            return false
        }

        if (userRepository.existsByEmail(newEmail)) {
            LOG.error("Email '{}' is already taken", newEmail)
            return false
        }

        user.email = newEmail
        userRepository.persist(user)

        LOG.info("Email changed for user '{}' to '{}'", user.username, newEmail)
        return true
    }
}
