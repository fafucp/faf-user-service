package com.faforever.userservice.ui.view.ucp

import com.faforever.userservice.backend.account.PasswordChangeConfirmationResult
import com.faforever.userservice.backend.account.PasswordChangeService
import com.faforever.userservice.ui.component.FafLogo
import com.faforever.userservice.ui.component.SocialIcons
import com.faforever.userservice.ui.layout.CardLayout
import com.faforever.userservice.ui.layout.CompactVerticalLayout
import com.faforever.userservice.ui.view.registration.ActivateView.PasswordConfirmation
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.PasswordField
import com.vaadin.flow.data.binder.Binder
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.BeforeEnterObserver
import com.vaadin.flow.router.Route
import com.vaadin.flow.server.auth.AnonymousAllowed

@Route("/ucp/password/confirm", layout = CardLayout::class)
@AnonymousAllowed
class UcpConfirmPasswordChangeView(
    private val passwordChangeService: PasswordChangeService,
) : CompactVerticalLayout(), BeforeEnterObserver {

    private val title = H2(getTranslation("ucp.changePassword.confirm.title"))
    private val message = Paragraph()
    private val passwordForm = VerticalLayout().apply {
        isVisible = false
    }
    private val newPassword = PasswordField(null, getTranslation("ucp.changePassword.newPassword")).apply {
        setWidthFull()
        isRequiredIndicatorVisible = true
    }
    private val confirmPassword = PasswordField(null, getTranslation("ucp.changePassword.confirmPassword")).apply {
        setWidthFull()
        isRequiredIndicatorVisible = true
    }
    private var token: String? = null

    private val binder = Binder(PasswordConfirmation::class.java)

    private val submitButton = Button(getTranslation("ucp.changePassword.confirm.submit")) { setPassword() }.apply {
        addThemeVariants(ButtonVariant.LUMO_PRIMARY)
        setWidthFull()
        isEnabled = false
    }

    init {
        maxWidth = "30rem"

        val formHeader = HorizontalLayout(FafLogo(), title).apply {
            justifyContentMode = FlexComponent.JustifyContentMode.CENTER
            alignItems = FlexComponent.Alignment.CENTER
            setId("form-header")
            setWidthFull()
        }

        passwordForm.add(
            newPassword,
            confirmPassword,
            submitButton,
        )

        add(
            formHeader,
            message,
            passwordForm,
            Button(getTranslation("ucp.changePassword.confirm.login")) {
                ui.ifPresent { it.navigate(UcpLoginView::class.java) }
            }.apply {
                addThemeVariants(ButtonVariant.LUMO_PRIMARY)
                setWidthFull()
            },
            VerticalLayout(SocialIcons()).apply {
                alignItems = FlexComponent.Alignment.CENTER
            },
        )

        binder.forField(newPassword)
            .asRequired(getTranslation("ucp.changePassword.newPasswordRequired"))
            .withValidator({ it.length >= 6 }, getTranslation("ucp.changePassword.newPasswordTooShort"))
            .bind("password")

        binder.forField(confirmPassword)
            .withValidator(
                { it == newPassword.value },
                getTranslation("ucp.changePassword.passwordsDoNotMatch"),
            ).bind("confirmedPassword")

        binder.addStatusChangeListener { submitButton.isEnabled = it.binder.isValid }
    }

    override fun beforeEnter(event: BeforeEnterEvent) {
        token = event.location.queryParameters.parameters["token"]?.firstOrNull()
        if (token.isNullOrBlank()) {
            message.text = getTranslation("ucp.changePassword.confirm.invalidToken")
            passwordForm.isVisible = false
        } else {
            message.text = getTranslation("ucp.changePassword.confirm.enterNewPassword")
            passwordForm.isVisible = true
        }
    }

    private fun setPassword() {
        if (!binder.validate().isOk) return
        if (token.isNullOrBlank()) {
            message.text = getTranslation("ucp.changePassword.confirm.invalidToken")
            return
        }

        val result = passwordChangeService.setPassword(token!!, newPassword.value)
        when (result) {
            PasswordChangeConfirmationResult.Confirmed -> {
                message.text = getTranslation("ucp.changePassword.confirm.success")
                passwordForm.isVisible = false
            }
            PasswordChangeConfirmationResult.InvalidToken ->
                message.text = getTranslation("ucp.changePassword.confirm.invalidToken")
            PasswordChangeConfirmationResult.PendingChangeNotFound ->
                message.text = getTranslation("ucp.changePassword.confirm.notFound")
            PasswordChangeConfirmationResult.UserNotFound ->
                message.text = getTranslation("ucp.changePassword.confirm.userNotFound")
            PasswordChangeConfirmationResult.PasswordUnchanged ->
                message.text = getTranslation("ucp.changePassword.confirm.passwordUnchanged")
        }
    }
}
