package com.faforever.userservice.ui.view.ucp

import com.faforever.userservice.backend.account.PasswordChangeRequestResult
import com.faforever.userservice.backend.account.PasswordChangeService
import com.faforever.userservice.backend.ucp.UcpSessionService
import com.faforever.userservice.ui.layout.UcpLayout
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.PasswordField
import com.vaadin.flow.data.value.ValueChangeMode
import com.vaadin.flow.router.Route
import jakarta.annotation.security.PermitAll

@Route(value = "/ucp/password", layout = UcpLayout::class)
@PermitAll
class UcpChangePasswordView(
    private val ucpSessionService: UcpSessionService,
    private val passwordChangeService: PasswordChangeService,
) : VerticalLayout() {

    private val currentPassword = PasswordField(null, getTranslation("ucp.changePassword.currentPassword")).apply {
        setWidthFull()
        valueChangeMode = ValueChangeMode.LAZY
        isRequiredIndicatorVisible = true
    }

    private val submit = Button(getTranslation("ucp.changePassword.submit")) { requestPasswordChange() }.apply {
        addThemeVariants(ButtonVariant.LUMO_PRIMARY)
    }

    init {
        setPadding(true)
        setSizeFull()
        add(
            H2(getTranslation("ucp.nav.changePassword")),
            Paragraph(getTranslation("ucp.changePassword.description")),
            currentPassword,
            submit,
        )
    }

    private fun requestPasswordChange() {
        val currentUser = ucpSessionService.getCurrentUser()
        when {
            currentPassword.value.isNullOrBlank() ->
                showError(getTranslation("ucp.changePassword.currentPasswordRequired"))
            !passwordChangeService.passwordCheck(currentUser.userId, currentPassword.value) ->
                showError(getTranslation("ucp.changePassword.currentPasswordIncorrect"))
            else -> {
                when (passwordChangeService.requestPasswordChange(currentUser.userId)) {
                    PasswordChangeRequestResult.ConfirmationSent -> {
                        Dialog().apply {
                            add(H2(getTranslation("ucp.changePassword.sent.title")))
                            add(Span(getTranslation("ucp.changePassword.sent.details")))
                            footer.add(
                                Button(getTranslation("ucp.changePassword.sent.close")) { close() }.apply {
                                    addThemeVariants(ButtonVariant.LUMO_PRIMARY)
                                },
                            )
                            open()
                        }
                        clearFields()
                    }
                    PasswordChangeRequestResult.UserNotFound ->
                        showError(getTranslation("ucp.changePassword.userNotFound"))
                }
            }
        }
    }

    private fun clearFields() {
        currentPassword.clear()
    }

    private fun showError(message: String) {
        Notification.show(message, 5000, Notification.Position.MIDDLE).apply {
            addThemeVariants(NotificationVariant.LUMO_ERROR)
        }
    }
}
