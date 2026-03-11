package com.faforever.userservice.ui.view.account

import com.faforever.userservice.backend.account.ChangeEmailResult
import com.faforever.userservice.backend.account.ChangePasswordResult
import com.faforever.userservice.backend.account.ChangeUsernameResult
import com.faforever.userservice.backend.account.UserService
import com.faforever.userservice.backend.domain.User
import com.faforever.userservice.backend.domain.UserRepository
import com.faforever.userservice.ui.component.FafLogo
import com.faforever.userservice.ui.component.SocialIcons
import com.faforever.userservice.ui.layout.CardLayout
import com.faforever.userservice.ui.layout.CompactVerticalLayout
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.html.H3
import com.vaadin.flow.component.html.Hr
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.PasswordField
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.value.ValueChangeMode
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.BeforeEnterObserver
import com.vaadin.flow.router.Route
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed

@Route("/account", layout = CardLayout::class)
@RolesAllowed("USER")
class AccountManagementView(
    private val userService: UserService,
    private val userRepository: UserRepository,
    private val securityIdentity: SecurityIdentity,
) : CompactVerticalLayout(), BeforeEnterObserver {

    private var user: User? = null

    // Change Password fields
    private val currentPassword = PasswordField(null, getTranslation("account.changePassword.currentPassword")).apply {
        setWidthFull()
    }
    private val newPassword = PasswordField(null, getTranslation("account.changePassword.newPassword")).apply {
        setWidthFull()
        valueChangeMode = ValueChangeMode.LAZY
    }
    private val confirmNewPassword =
        PasswordField(null, getTranslation("account.changePassword.confirmNewPassword")).apply {
            setWidthFull()
            valueChangeMode = ValueChangeMode.LAZY
        }
    private val changePasswordButton =
        Button(getTranslation("account.changePassword.submit")) { changePassword() }.apply {
            addThemeVariants(ButtonVariant.LUMO_PRIMARY)
            setWidthFull()
        }

    // Change Email fields
    private val newEmail = TextField(null, getTranslation("account.changeEmail.newEmail")).apply {
        setWidthFull()
        valueChangeMode = ValueChangeMode.LAZY
    }
    private val changeEmailButton = Button(getTranslation("account.changeEmail.submit")) { changeEmail() }.apply {
        addThemeVariants(ButtonVariant.LUMO_PRIMARY)
        setWidthFull()
    }

    // Change Username fields
    private val newUsername = TextField(null, getTranslation("account.changeUsername.newUsername")).apply {
        setWidthFull()
        valueChangeMode = ValueChangeMode.LAZY
    }
    private val changeUsernameButton =
        Button(getTranslation("account.changeUsername.submit")) { changeUsername() }.apply {
            addThemeVariants(ButtonVariant.LUMO_PRIMARY)
            setWidthFull()
        }

    init {
        val formHeaderLeft = FafLogo()
        val formHeaderRight = H2(getTranslation("account.title"))
        val formHeader = HorizontalLayout(formHeaderLeft, formHeaderRight).apply {
            justifyContentMode = FlexComponent.JustifyContentMode.CENTER
            alignItems = FlexComponent.Alignment.CENTER
            setId("form-header")
            setWidthFull()
        }
        add(formHeader)

        // Change Password section
        add(H3(getTranslation("account.changePassword")))
        add(currentPassword, newPassword, confirmNewPassword, changePasswordButton)
        add(Hr())

        // Change Email section
        add(H3(getTranslation("account.changeEmail")))
        add(newEmail, changeEmailButton)
        add(Hr())

        // Change Username section
        add(H3(getTranslation("account.changeUsername")))
        add(newUsername, changeUsernameButton)

        val footer = VerticalLayout(SocialIcons()).apply {
            alignItems = FlexComponent.Alignment.CENTER
        }
        add(footer)
    }

    private fun changePassword() {
        val currentUser = user ?: return

        if (newPassword.value.length < 6) {
            showDialog(getTranslation("register.password.size"))
            return
        }

        if (newPassword.value != confirmNewPassword.value) {
            showDialog(getTranslation("register.password.match"))
            return
        }

        when (userService.changePassword(currentUser, currentPassword.value, newPassword.value)) {
            is ChangePasswordResult.Success -> {
                showDialog(getTranslation("account.changePassword.success"))
                currentPassword.clear()
                newPassword.clear()
                confirmNewPassword.clear()
            }
            is ChangePasswordResult.InvalidCurrentPassword ->
                showDialog(getTranslation("account.changePassword.invalidCurrentPassword"))
        }
    }

    private fun changeEmail() {
        val currentUser = user ?: return
        val email = newEmail.value

        if (email.isBlank()) {
            return
        }

        when (userService.requestEmailChange(currentUser, email)) {
            is ChangeEmailResult.EmailSent -> {
                showDialog(getTranslation("account.changeEmail.success"))
                newEmail.clear()
            }
            is ChangeEmailResult.EmailBlacklisted ->
                showDialog(getTranslation("account.changeEmail.blacklisted"))
            is ChangeEmailResult.EmailTaken ->
                showDialog(getTranslation("account.changeEmail.taken"))
        }
    }

    private fun changeUsername() {
        val currentUser = user ?: return
        val username = newUsername.value

        if (username.isBlank() || username.length < 3 || username.length > 15) {
            showDialog(getTranslation("register.username.size"))
            return
        }

        if (!username[0].isLetter()) {
            showDialog(getTranslation("register.username.startsWithLetter"))
            return
        }

        if (Regex("[^A-Za-z0-9_-]").containsMatchIn(username)) {
            showDialog(getTranslation("register.username.alphanumeric"))
            return
        }

        when (userService.changeUsername(currentUser, username)) {
            is ChangeUsernameResult.Success -> {
                showDialog(getTranslation("account.changeUsername.success"))
                newUsername.clear()
            }
            is ChangeUsernameResult.UsernameTaken ->
                showDialog(getTranslation("account.changeUsername.taken"))
            is ChangeUsernameResult.UsernameReserved ->
                showDialog(getTranslation("account.changeUsername.reserved"))
            is ChangeUsernameResult.TooEarly ->
                showDialog(getTranslation("account.changeUsername.tooEarly"))
        }
    }

    private fun showDialog(message: String) {
        Dialog().apply {
            add(Span(message))
            open()
        }
    }

    override fun beforeEnter(event: BeforeEnterEvent?) {
        val principal = securityIdentity.principal
        user = userRepository.findByUsernameOrEmail(principal.name)
    }
}
