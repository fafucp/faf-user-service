package com.faforever.userservice.backend.account

import com.faforever.userservice.backend.domain.NameRecordRepository
import com.faforever.userservice.backend.domain.User
import com.faforever.userservice.backend.domain.UserRepository
import com.faforever.userservice.backend.email.EmailService
import com.faforever.userservice.backend.security.FafTokenService
import com.faforever.userservice.backend.security.FafTokenType
import com.faforever.userservice.config.FafProperties
import io.quarkus.mailer.MockMailbox
import io.quarkus.test.InjectMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.mockito.InjectSpy
import jakarta.inject.Inject
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.instanceOf
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@QuarkusTest
class UserServiceTest {

    companion object {
        private const val USERNAME = "testUser"
        private const val EMAIL = "test@email.com"
        private const val PASSWORD = "testPassword"
        private const val ENCODED_PASSWORD = "encodedPassword"

        private val user = User(1, USERNAME, PASSWORD, EMAIL, null, null)
    }

    @Inject
    private lateinit var userService: UserService

    @Inject
    private lateinit var mailbox: MockMailbox

    @Inject
    private lateinit var fafProperties: FafProperties

    @InjectMock
    private lateinit var userRepository: UserRepository

    @InjectMock
    private lateinit var nameRecordRepository: NameRecordRepository

    @InjectMock
    private lateinit var fafTokenService: FafTokenService

    @InjectSpy
    private lateinit var emailService: EmailService

    @BeforeEach
    fun setup() {
        mailbox.clear()
    }

    @Test
    fun changePasswordSuccess() {
        val testUser = User(1, USERNAME, "a665a45920422f9d417e4867efdc4fb8a04a1f3fff1fa07e998e86f7f7a27ae3", EMAIL, null, null)
        whenever(userRepository.findById(1)).thenReturn(testUser)

        val result = userService.changePassword(testUser, "123", "newPassword")

        assertThat(result, instanceOf(ChangePasswordResult.Success::class.java))
    }

    @Test
    fun changePasswordInvalidCurrentPassword() {
        val testUser = User(1, USERNAME, "a665a45920422f9d417e4867efdc4fb8a04a1f3fff1fa07e998e86f7f7a27ae3", EMAIL, null, null)

        val result = userService.changePassword(testUser, "wrongPassword", "newPassword")

        assertThat(result, instanceOf(ChangePasswordResult.InvalidCurrentPassword::class.java))
    }

    @Test
    fun changeUsernameSuccess() {
        val testUser = User(1, USERNAME, PASSWORD, EMAIL, null, null)
        whenever(userRepository.existsByUsername("newUsername")).thenReturn(false)
        whenever(nameRecordRepository.existsByUserIdAndChangeTimeAfter(anyInt(), any())).thenReturn(false)
        whenever(nameRecordRepository.existsByPreviousNameAndChangeTimeAfterAndUserIdNotEquals(anyString(), any(), anyInt())).thenReturn(false)

        val result = userService.changeUsername(testUser, "newUsername")

        assertThat(result, instanceOf(ChangeUsernameResult.Success::class.java))
    }

    @Test
    fun changeUsernameTaken() {
        val testUser = User(1, USERNAME, PASSWORD, EMAIL, null, null)
        whenever(userRepository.existsByUsername("takenUsername")).thenReturn(true)
        whenever(nameRecordRepository.existsByUserIdAndChangeTimeAfter(anyInt(), any())).thenReturn(false)

        val result = userService.changeUsername(testUser, "takenUsername")

        assertThat(result, instanceOf(ChangeUsernameResult.UsernameTaken::class.java))
    }

    @Test
    fun changeUsernameReserved() {
        val testUser = User(1, USERNAME, PASSWORD, EMAIL, null, null)
        whenever(userRepository.existsByUsername("reservedUsername")).thenReturn(false)
        whenever(nameRecordRepository.existsByUserIdAndChangeTimeAfter(anyInt(), any())).thenReturn(false)
        whenever(nameRecordRepository.existsByPreviousNameAndChangeTimeAfterAndUserIdNotEquals(anyString(), any(), anyInt())).thenReturn(true)

        val result = userService.changeUsername(testUser, "reservedUsername")

        assertThat(result, instanceOf(ChangeUsernameResult.UsernameReserved::class.java))
    }

    @Test
    fun changeUsernameTooEarly() {
        val testUser = User(1, USERNAME, PASSWORD, EMAIL, null, null)
        whenever(nameRecordRepository.existsByUserIdAndChangeTimeAfter(anyInt(), any())).thenReturn(true)

        val result = userService.changeUsername(testUser, "newUsername")

        assertThat(result, instanceOf(ChangeUsernameResult.TooEarly::class.java))
    }

    @Test
    fun requestEmailChangeSuccess() {
        val testUser = User(1, USERNAME, PASSWORD, EMAIL, null, null)
        whenever(userRepository.existsByEmail("new@email.com")).thenReturn(false)
        whenever(fafTokenService.createToken(eq(FafTokenType.EMAIL_CHANGE), any(), any())).thenReturn("testToken")

        val result = userService.requestEmailChange(testUser, "new@email.com")

        assertThat(result, instanceOf(ChangeEmailResult.EmailSent::class.java))
        verify(emailService).sendEmailChangeMail(eq(USERNAME), eq("new@email.com"), any())
    }

    @Test
    fun requestEmailChangeTaken() {
        val testUser = User(1, USERNAME, PASSWORD, EMAIL, null, null)
        whenever(userRepository.existsByEmail("taken@email.com")).thenReturn(true)

        val result = userService.requestEmailChange(testUser, "taken@email.com")

        assertThat(result, instanceOf(ChangeEmailResult.EmailTaken::class.java))
    }

    @Test
    fun confirmEmailChangeSuccess() {
        whenever(fafTokenService.getTokenClaims(FafTokenType.EMAIL_CHANGE, "validToken"))
            .thenReturn(mapOf("id" to "1", "newEmail" to "new@email.com"))
        whenever(userRepository.findById(1)).thenReturn(User(1, USERNAME, PASSWORD, EMAIL, null, null))
        whenever(userRepository.existsByEmail("new@email.com")).thenReturn(false)

        val result = userService.confirmEmailChange("validToken")

        assertThat(result, `is`(true))
    }

    @Test
    fun confirmEmailChangeInvalidToken() {
        whenever(fafTokenService.getTokenClaims(FafTokenType.EMAIL_CHANGE, "invalidToken"))
            .thenThrow(RuntimeException("invalid"))

        val result = userService.confirmEmailChange("invalidToken")

        assertThat(result, `is`(false))
    }

    @Test
    fun confirmEmailChangeUserNotFound() {
        whenever(fafTokenService.getTokenClaims(FafTokenType.EMAIL_CHANGE, "validToken"))
            .thenReturn(mapOf("id" to "999", "newEmail" to "new@email.com"))
        whenever(userRepository.findById(999)).thenReturn(null)

        val result = userService.confirmEmailChange("validToken")

        assertThat(result, `is`(false))
    }

    @Test
    fun confirmEmailChangeEmailAlreadyTaken() {
        whenever(fafTokenService.getTokenClaims(FafTokenType.EMAIL_CHANGE, "validToken"))
            .thenReturn(mapOf("id" to "1", "newEmail" to "taken@email.com"))
        whenever(userRepository.findById(1)).thenReturn(User(1, USERNAME, PASSWORD, EMAIL, null, null))
        whenever(userRepository.existsByEmail("taken@email.com")).thenReturn(true)

        val result = userService.confirmEmailChange("validToken")

        assertThat(result, `is`(false))
    }
}
