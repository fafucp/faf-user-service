package com.faforever.userservice.ui.view.account

import com.faforever.userservice.backend.account.UserService
import com.faforever.userservice.ui.component.FafLogo
import com.faforever.userservice.ui.component.SocialIcons
import com.faforever.userservice.ui.layout.CardLayout
import com.faforever.userservice.ui.layout.CompactVerticalLayout
import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.BeforeEnterObserver
import com.vaadin.flow.router.Route

@Route("/account/confirm-email", layout = CardLayout::class)
class ConfirmEmailChangeView(
    private val userService: UserService,
) : CompactVerticalLayout(), BeforeEnterObserver {

    private val resultMessage = Span()

    init {
        val formHeaderLeft = FafLogo()
        val formHeaderRight = H2(getTranslation("account.changeEmail"))
        val formHeader = HorizontalLayout(formHeaderLeft, formHeaderRight).apply {
            justifyContentMode = FlexComponent.JustifyContentMode.CENTER
            alignItems = FlexComponent.Alignment.CENTER
            setId("form-header")
            setWidthFull()
        }
        add(formHeader)
        add(resultMessage)

        val footer = VerticalLayout(SocialIcons()).apply {
            alignItems = FlexComponent.Alignment.CENTER
        }
        add(footer)
    }

    override fun beforeEnter(event: BeforeEnterEvent?) {
        val token = event?.location?.queryParameters?.parameters?.get("token")?.firstOrNull()

        if (token != null && userService.confirmEmailChange(token)) {
            resultMessage.text = getTranslation("account.changeEmail.confirmed")
        } else {
            resultMessage.text = getTranslation("account.changeEmail.failed")
        }
    }
}
