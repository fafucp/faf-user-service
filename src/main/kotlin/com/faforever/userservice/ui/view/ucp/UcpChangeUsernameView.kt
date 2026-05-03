package com.faforever.userservice.ui.view.ucp

import com.faforever.userservice.backend.ucp.UcpSessionService
import com.faforever.userservice.backend.ucp.UcpUsernameService
import com.faforever.userservice.ui.layout.UcpLayout
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.router.Route

@Route(value = "/ucp/username", layout = UcpLayout::class)
class UcpChangeUsernameView(
    private val ucpUsernameService: UcpUsernameService,
    private val ucpSessionService: UcpSessionService,
) : VerticalLayout() {

    private val newUsernameField = TextField().apply {
        label = "New Username"
        setWidthFull()
    }

    private val currentUsernameDisplay = Paragraph().apply {
        setWidthFull()
    }

    private val submitButton = Button("Change Username") { handleChangeUsername() }.apply {
        addThemeVariants(ButtonVariant.LUMO_PRIMARY)
    }

    init {
        setPadding(true)
        setSizeFull()

        add(H2(getTranslation("ucp.nav.changeUsername")))

        // Current username section
        currentUsernameDisplay.text = "Current username: ${ucpSessionService.getCurrentUser()?.userName ?: "Unknown"}"
        add(currentUsernameDisplay)

        // Form section
        add(newUsernameField)

        // Username rules
        val rulesText = """
            Username rules:
            • Must start with a letter
            • Must be between 3 and 15 characters
            • Allowed characters: letters, numbers, underscores, and dashes
            • Must be different from current username
        """.trimIndent()
        add(Paragraph(rulesText).apply {
            style.set("font-size", "var(--lumo-font-size-s)")
            style.set("color", "var(--lumo-secondary-text-color)")
        })

        add(submitButton)
    }

    private fun handleChangeUsername() {
        val newUsername = newUsernameField.value
        val result = ucpUsernameService.changeUsername(newUsername)

        when (result) {
            is UcpUsernameService.UsernameChangeResult.Success -> {
                Notification.show("Username changed successfully", 3000, Notification.Position.TOP_CENTER)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
                newUsernameField.clear()
                // Refresh the view to update the current username display
                UI.getCurrent().navigate(UcpChangeUsernameView::class.java)
            }
            is UcpUsernameService.UsernameChangeResult.ValidationError -> {
                Notification.show(result.message, 3000, Notification.Position.TOP_CENTER)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR)
            }
            UcpUsernameService.UsernameChangeResult.NotLoggedIn -> {
                Notification.show("You must be logged in to change your username", 3000, Notification.Position.TOP_CENTER)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR)
            }
        }
    }
}
