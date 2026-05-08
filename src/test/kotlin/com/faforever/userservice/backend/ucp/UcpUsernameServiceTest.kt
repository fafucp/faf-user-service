package com.faforever.userservice.backend.ucp

import com.faforever.userservice.backend.account.RegistrationService
import com.faforever.userservice.backend.account.UsernameStatus
import com.faforever.userservice.backend.domain.NameRecord
import com.faforever.userservice.backend.domain.NameRecordRepository
import com.faforever.userservice.backend.domain.UserRepository
import com.faforever.userservice.config.FafProperties
import io.quarkus.test.InjectMock
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@QuarkusTest
class UcpUsernameServiceTest {

    @Inject
    lateinit var service: UcpUsernameService

    @InjectMock
    lateinit var userRepository: UserRepository

    @InjectMock
    lateinit var nameRecordRepository: NameRecordRepository

    @InjectMock
    lateinit var ucpSessionService: UcpSessionService

    @InjectMock
    lateinit var registrationService: RegistrationService

    @Inject
    lateinit var fafProperties: FafProperties

    @BeforeEach
    fun setup() {
        whenever(nameRecordRepository.existsByUserIdAndChangeTimeAfter(any(), any())).thenReturn(false)
    }

    companion object {
        private const val USER_ID = 123
        private const val USERNAME = "TestUser"
        private const val NEW_USERNAME = "NewName"
        private const val LONG_USERNAME = "ThisIsAVeryLongUsernameThatExceedsTwentyCharacters"
    }

    @Test
    fun `changeUsername returns NotLoggedIn when user is not logged in`() {
        whenever(ucpSessionService.getCurrentUser()).thenReturn(null)

        val result = service.changeUsername(NEW_USERNAME)

        assertEquals(UcpUsernameService.UsernameChangeResult.NotLoggedIn, result)
    }

    @Test
    fun `changeUsername returns ValidationError for empty username`() {
        val user = UcpUser(USER_ID, USERNAME)
        whenever(ucpSessionService.getCurrentUser()).thenReturn(user)

        val result = service.changeUsername("")

        assertTrue(result is UcpUsernameService.UsernameChangeResult.ValidationError)
        assertEquals(
            "ucp.username.error.empty",
            (result as UcpUsernameService.UsernameChangeResult.ValidationError).message,
        )
    }

    @Test
    fun `changeUsername returns ValidationError for too short username`() {
        val user = UcpUser(USER_ID, USERNAME)
        whenever(ucpSessionService.getCurrentUser()).thenReturn(user)

        val result = service.changeUsername("ab")

        assertTrue(result is UcpUsernameService.UsernameChangeResult.ValidationError)
        assertEquals(
            "ucp.username.error.length",
            (result as UcpUsernameService.UsernameChangeResult.ValidationError).message,
        )
    }

    @Test
    fun `changeUsername returns ValidationError for too long username`() {
        val user = UcpUser(USER_ID, USERNAME)
        whenever(ucpSessionService.getCurrentUser()).thenReturn(user)

        val result = service.changeUsername(LONG_USERNAME)

        assertTrue(result is UcpUsernameService.UsernameChangeResult.ValidationError)
        assertEquals(
            "ucp.username.error.length",
            (result as UcpUsernameService.UsernameChangeResult.ValidationError).message,
        )
    }

    @Test
    fun `changeUsername returns ValidationError when username contains invalid characters`() {
        val user = UcpUser(USER_ID, USERNAME)
        whenever(ucpSessionService.getCurrentUser()).thenReturn(user)

        val result = service.changeUsername("Bad*Name")

        assertTrue(result is UcpUsernameService.UsernameChangeResult.ValidationError)
        assertEquals(
            "ucp.username.error.invalidCharacters",
            (result as UcpUsernameService.UsernameChangeResult.ValidationError).message,
        )
    }

    @Test
    fun `changeUsername returns ValidationError when username does not start with a letter`() {
        val user = UcpUser(USER_ID, USERNAME)
        whenever(ucpSessionService.getCurrentUser()).thenReturn(user)

        val result = service.changeUsername("_startsWithUnderscore")

        assertTrue(result is UcpUsernameService.UsernameChangeResult.ValidationError)
        assertEquals(
            "ucp.username.error.mustStartWithLetter",
            (result as UcpUsernameService.UsernameChangeResult.ValidationError).message,
        )
    }

    @Test
    fun `changeUsername returns ValidationError when new username is same as current`() {
        val user = UcpUser(USER_ID, USERNAME)
        whenever(ucpSessionService.getCurrentUser()).thenReturn(user)

        val result = service.changeUsername(USERNAME)

        assertTrue(result is UcpUsernameService.UsernameChangeResult.ValidationError)
        assertEquals(
            "ucp.username.error.sameAsCurrent",
            (result as UcpUsernameService.UsernameChangeResult.ValidationError).message,
        )
    }

    @Test
    fun `changeUsername returns ValidationError when username is already taken`() {
        val user = UcpUser(USER_ID, USERNAME)
        whenever(ucpSessionService.getCurrentUser()).thenReturn(user)
        whenever(registrationService.usernameAvailable(NEW_USERNAME)).thenReturn(UsernameStatus.USERNAME_TAKEN)

        val result = service.changeUsername(NEW_USERNAME)

        assertTrue(result is UcpUsernameService.UsernameChangeResult.ValidationError)
        assertEquals(
            "ucp.username.error.taken",
            (result as UcpUsernameService.UsernameChangeResult.ValidationError).message,
        )
    }

    @Test
    fun `changeUsername returns ValidationError when username is reserved`() {
        val user = UcpUser(USER_ID, USERNAME)
        whenever(ucpSessionService.getCurrentUser()).thenReturn(user)
        whenever(registrationService.usernameAvailable(NEW_USERNAME)).thenReturn(UsernameStatus.USERNAME_RESERVED)

        val result = service.changeUsername(NEW_USERNAME)

        assertTrue(result is UcpUsernameService.UsernameChangeResult.ValidationError)
        assertEquals(
            "ucp.username.error.reserved",
            (result as UcpUsernameService.UsernameChangeResult.ValidationError).message,
        )
    }

    @Test
    fun `changeUsername succeeds and updates username`() {
        val user = UcpUser(USER_ID, USERNAME)
        whenever(ucpSessionService.getCurrentUser()).thenReturn(user)
        whenever(registrationService.usernameAvailable(NEW_USERNAME)).thenReturn(UsernameStatus.USERNAME_AVAILABLE)

        val result = service.changeUsername(NEW_USERNAME)

        assertTrue(result is UcpUsernameService.UsernameChangeResult.Success)
        val success = result as UcpUsernameService.UsernameChangeResult.Success
        assertEquals(USER_ID, success.userId)
        assertEquals(NEW_USERNAME, success.newUsername)

        verify(userRepository).updateUsername(USER_ID, NEW_USERNAME)
        verify(nameRecordRepository).persist(any<NameRecord>())
        verify(ucpSessionService).setCurrentUser(UcpUser(USER_ID, NEW_USERNAME))
    }

    @Test
    fun `changeUsername returns ValidationError when username change cooldown is active`() {
        val user = UcpUser(USER_ID, USERNAME)
        whenever(ucpSessionService.getCurrentUser()).thenReturn(user)
        whenever(nameRecordRepository.existsByUserIdAndChangeTimeAfter(any(), any())).thenReturn(true)

        val result = service.changeUsername(NEW_USERNAME)

        assertTrue(result is UcpUsernameService.UsernameChangeResult.ValidationError)
        assertEquals(
            "ucp.username.error.cooldown",
            (result as UcpUsernameService.UsernameChangeResult.ValidationError).message,
        )
    }

    @Test
    fun `changeUsername trims whitespace from username`() {
        val user = UcpUser(USER_ID, USERNAME)
        whenever(ucpSessionService.getCurrentUser()).thenReturn(user)
        whenever(registrationService.usernameAvailable(NEW_USERNAME)).thenReturn(UsernameStatus.USERNAME_AVAILABLE)

        val result = service.changeUsername("  $NEW_USERNAME  ")

        assertTrue(result is UcpUsernameService.UsernameChangeResult.Success)
        verify(userRepository).updateUsername(USER_ID, NEW_USERNAME)
        verify(ucpSessionService).setCurrentUser(UcpUser(USER_ID, NEW_USERNAME))
    }

    @Test
    fun `changeUsername throws exception when repository update fails`() {
        val user = UcpUser(USER_ID, USERNAME)
        whenever(ucpSessionService.getCurrentUser()).thenReturn(user)
        whenever(registrationService.usernameAvailable(NEW_USERNAME)).thenReturn(UsernameStatus.USERNAME_AVAILABLE)
        whenever(userRepository.updateUsername(USER_ID, NEW_USERNAME)).thenThrow(RuntimeException("DB error"))

        assertThrows(RuntimeException::class.java) {
            service.changeUsername(NEW_USERNAME)
        }
    }
}
