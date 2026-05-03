package com.faforever.userservice.backend.ucp

import com.faforever.userservice.backend.account.RegistrationService
import com.faforever.userservice.backend.account.UsernameStatus
import com.faforever.userservice.backend.domain.UserRepository
import io.quarkus.test.InjectMock
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.*
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
    lateinit var ucpSessionService: UcpSessionService

    @InjectMock
    lateinit var registrationService: RegistrationService

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
        assertEquals("Username cannot be empty", (result as UcpUsernameService.UsernameChangeResult.ValidationError).message)
    }

    @Test
    fun `changeUsername returns ValidationError for too short username`() {
        val user = UcpUser(USER_ID, USERNAME)
        whenever(ucpSessionService.getCurrentUser()).thenReturn(user)

        val result = service.changeUsername("ab")

        assertTrue(result is UcpUsernameService.UsernameChangeResult.ValidationError)
        assertEquals("Username must be between 3 and 15 characters", (result as UcpUsernameService.UsernameChangeResult.ValidationError).message)
    }

    @Test
    fun `changeUsername returns ValidationError for too long username`() {
        val user = UcpUser(USER_ID, USERNAME)
        whenever(ucpSessionService.getCurrentUser()).thenReturn(user)

        val result = service.changeUsername(LONG_USERNAME)

        assertTrue(result is UcpUsernameService.UsernameChangeResult.ValidationError)
        assertEquals("Username must be between 3 and 15 characters", (result as UcpUsernameService.UsernameChangeResult.ValidationError).message)
    }

    @Test
    fun `changeUsername returns ValidationError when username contains invalid characters`() {
        val user = UcpUser(USER_ID, USERNAME)
        whenever(ucpSessionService.getCurrentUser()).thenReturn(user)

        val result = service.changeUsername("Bad*Name")

        assertTrue(result is UcpUsernameService.UsernameChangeResult.ValidationError)
        assertEquals("Username can only contain letters, numbers, underscores, and dashes", (result as UcpUsernameService.UsernameChangeResult.ValidationError).message)
    }

    @Test
    fun `changeUsername returns ValidationError when username does not start with a letter`() {
        val user = UcpUser(USER_ID, USERNAME)
        whenever(ucpSessionService.getCurrentUser()).thenReturn(user)

        val result = service.changeUsername("_startsWithUnderscore")

        assertTrue(result is UcpUsernameService.UsernameChangeResult.ValidationError)
        assertEquals("Username must start with a letter", (result as UcpUsernameService.UsernameChangeResult.ValidationError).message)
    }

    @Test
    fun `changeUsername returns ValidationError when new username is same as current`() {
        val user = UcpUser(USER_ID, USERNAME)
        whenever(ucpSessionService.getCurrentUser()).thenReturn(user)

        val result = service.changeUsername(USERNAME)

        assertTrue(result is UcpUsernameService.UsernameChangeResult.ValidationError)
        assertEquals("New username must be different from current username", (result as UcpUsernameService.UsernameChangeResult.ValidationError).message)
    }

    @Test
    fun `changeUsername returns ValidationError when username is already taken`() {
        val user = UcpUser(USER_ID, USERNAME)
        whenever(ucpSessionService.getCurrentUser()).thenReturn(user)
        whenever(registrationService.usernameAvailable(NEW_USERNAME)).thenReturn(UsernameStatus.USERNAME_TAKEN)

        val result = service.changeUsername(NEW_USERNAME)

        assertTrue(result is UcpUsernameService.UsernameChangeResult.ValidationError)
        assertEquals("Username is already taken", (result as UcpUsernameService.UsernameChangeResult.ValidationError).message)
    }

    @Test
    fun `changeUsername returns ValidationError when username is reserved`() {
        val user = UcpUser(USER_ID, USERNAME)
        whenever(ucpSessionService.getCurrentUser()).thenReturn(user)
        whenever(registrationService.usernameAvailable(NEW_USERNAME)).thenReturn(UsernameStatus.USERNAME_RESERVED)

        val result = service.changeUsername(NEW_USERNAME)

        assertTrue(result is UcpUsernameService.UsernameChangeResult.ValidationError)
        assertEquals("Username is reserved", (result as UcpUsernameService.UsernameChangeResult.ValidationError).message)
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
        verify(ucpSessionService).setCurrentUser(UcpUser(USER_ID, NEW_USERNAME))
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
    fun `changeUsername returns ValidationError when repository update fails`() {
        val user = UcpUser(USER_ID, USERNAME)
        whenever(ucpSessionService.getCurrentUser()).thenReturn(user)
        whenever(registrationService.usernameAvailable(NEW_USERNAME)).thenReturn(UsernameStatus.USERNAME_AVAILABLE)
        whenever(userRepository.updateUsername(USER_ID, NEW_USERNAME)).thenThrow(RuntimeException("DB error"))

        val result = service.changeUsername(NEW_USERNAME)

        assertTrue(result is UcpUsernameService.UsernameChangeResult.ValidationError)
        assertEquals("Unable to change username. Please try again later.", (result as UcpUsernameService.UsernameChangeResult.ValidationError).message)
    }
}