package com.faforever.userservice.ui.view.ucp

import com.faforever.userservice.backend.domain.FriendOrFoeEntry
import com.faforever.userservice.backend.domain.SocialStatus
import com.faforever.userservice.backend.ucp.UcpFriendsFoesService
import com.faforever.userservice.backend.ucp.UcpSessionService
import com.faforever.userservice.ui.layout.UcpLayout
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.html.H3
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.BeforeEnterObserver
import com.vaadin.flow.router.Route
import jakarta.annotation.security.PermitAll

@Route(value = "/ucp/friends-foes", layout = UcpLayout::class)
@PermitAll
class UcpFriendsFoesView(
    private val ucpSessionService: UcpSessionService,
    private val ucpFriendsFoesService: UcpFriendsFoesService,
) : VerticalLayout(),
    BeforeEnterObserver {

    companion object {
        private const val NOTIFICATION_DURATION_MS = 3000
    }

    private val usernameField = TextField().apply {
        label = getTranslation("ucp.friendsFoes.usernameField")
    }

    private val addFriendButton = Button(getTranslation("ucp.friendsFoes.addFriend")) {
        handleAdd(SocialStatus.FRIEND)
    }.apply {
        addThemeVariants(ButtonVariant.LUMO_PRIMARY)
    }

    private val addFoeButton = Button(getTranslation("ucp.friendsFoes.addFoe")) {
        handleAdd(SocialStatus.FOE)
    }.apply {
        addThemeVariants(ButtonVariant.LUMO_ERROR)
    }

    private val friendsGrid = friendOrFoeGrid("ucp.friendsFoes.noFriends")
    private val foesGrid = friendOrFoeGrid("ucp.friendsFoes.noFoes")

    init {
        setPadding(true)
        setSizeFull()

        add(H2(getTranslation("ucp.nav.friendsFoes")))

        add(
            HorizontalLayout(usernameField, addFriendButton, addFoeButton).apply {
                setWidthFull()
                alignItems = FlexComponent.Alignment.BASELINE
                setFlexGrow(1.0, usernameField)
            },
        )

        add(H3(getTranslation("ucp.friendsFoes.friends.heading")))
        add(friendsGrid)
        add(H3(getTranslation("ucp.friendsFoes.foes.heading")))
        add(foesGrid)
    }

    override fun beforeEnter(event: BeforeEnterEvent) {
        refreshGrids()
    }

    private fun friendOrFoeGrid(emptyStateKey: String): Grid<FriendOrFoeEntry> =
        Grid(FriendOrFoeEntry::class.java, false).apply {
            addColumn(FriendOrFoeEntry::username).setHeader(getTranslation("ucp.friendsFoes.column.username"))
            addComponentColumn { entry ->
                Button(getTranslation("ucp.friendsFoes.remove")) { handleRemove(entry) }
            }
            setEmptyStateText(getTranslation(emptyStateKey))
            setWidthFull()
        }

    private fun handleAdd(status: SocialStatus) {
        val currentUser = ucpSessionService.getCurrentUser()
        when (val result = ucpFriendsFoesService.add(currentUser.userId, usernameField.value, status)) {
            is UcpFriendsFoesService.AddResult.Success -> {
                val successKey = if (status == SocialStatus.FRIEND) {
                    "ucp.friendsFoes.success.addedFriend"
                } else {
                    "ucp.friendsFoes.success.addedFoe"
                }
                notify(getTranslation(successKey, result.entry.username), NotificationVariant.LUMO_SUCCESS)
                usernameField.clear()
                refreshGrids()
            }
            is UcpFriendsFoesService.AddResult.ValidationError -> {
                notify(getTranslation(result.message), NotificationVariant.LUMO_ERROR)
            }
        }
    }

    private fun handleRemove(entry: FriendOrFoeEntry) {
        val currentUser = ucpSessionService.getCurrentUser()
        when (ucpFriendsFoesService.remove(currentUser.userId, entry.subjectId)) {
            UcpFriendsFoesService.RemoveResult.Success -> {
                notify(
                    getTranslation("ucp.friendsFoes.success.removed", entry.username),
                    NotificationVariant.LUMO_SUCCESS,
                )
                refreshGrids()
            }
            UcpFriendsFoesService.RemoveResult.NotFound -> {
                notify(getTranslation("ucp.friendsFoes.error.removeFailed"), NotificationVariant.LUMO_ERROR)
            }
        }
    }

    private fun refreshGrids() {
        val currentUser = ucpSessionService.getCurrentUser()
        friendsGrid.setItems(ucpFriendsFoesService.getFriends(currentUser.userId))
        foesGrid.setItems(ucpFriendsFoesService.getFoes(currentUser.userId))
    }

    private fun notify(message: String, variant: NotificationVariant) {
        Notification.show(message, NOTIFICATION_DURATION_MS, Notification.Position.TOP_CENTER)
            .addThemeVariants(variant)
    }
}
