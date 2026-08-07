package com.faforever.userservice.ui.view.ucp

import com.faforever.userservice.backend.domain.BanLevel
import com.faforever.userservice.backend.ucp.UcpBanHistoryEntry
import com.faforever.userservice.backend.ucp.UcpBanHistoryService
import com.faforever.userservice.backend.ucp.UcpBanStatus
import com.faforever.userservice.backend.ucp.UcpSessionService
import com.faforever.userservice.ui.layout.UcpLayout
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.grid.Grid.SelectionMode
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.html.H3
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.data.renderer.ComponentRenderer
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.BeforeEnterObserver
import com.vaadin.flow.router.Route
import jakarta.annotation.security.PermitAll
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max

@Route(value = "/ucp/ban-history", layout = UcpLayout::class)
@PermitAll
class UcpBanHistoryView(
    private val ucpSessionService: UcpSessionService,
    private val ucpBanHistoryService: UcpBanHistoryService,
) : VerticalLayout(), BeforeEnterObserver {

    private companion object {
        private const val GRID_WIDTH = "70rem"
        private const val DETAILS_CARD_WIDTH = "44rem"
    }

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private val activeBansTitle = H3()
    private val historyBansTitle = H3()

    private val activeBansGrid = createBansGrid(getTranslation("ucp.banHistory.noActiveBans"))
    private val historyBansGrid = createBansGrid(getTranslation("ucp.banHistory.noHistoryBans"))

    init {
        isPadding = true
        isSpacing = true
        setSizeFull()
        setAlignItems(FlexComponent.Alignment.START)

        add(
            H2(getTranslation("ucp.nav.banHistory")),
            activeBansTitle,
            activeBansGrid,
            historyBansTitle,
            historyBansGrid,
        )
    }

    override fun beforeEnter(event: BeforeEnterEvent) {
        val user = ucpSessionService.getCurrentUser()
        val bans = ucpBanHistoryService.getBanHistoryForUser(user.userId)

        val activeBans = bans.filter { it.status == UcpBanStatus.ACTIVE }
        val historyBans = bans.filter { it.status != UcpBanStatus.ACTIVE }

        activeBansTitle.text = getTranslation("ucp.banHistory.activeTitle", activeBans.size)
        historyBansTitle.text = getTranslation("ucp.banHistory.historyTitle", historyBans.size)

        activeBansGrid.setItems(activeBans)
        historyBansGrid.setItems(historyBans)
    }

    private fun createBansGrid(emptyStateText: String): Grid<UcpBanHistoryEntry> {
        var expandedItem: UcpBanHistoryEntry? = null

        return Grid(UcpBanHistoryEntry::class.java, false).apply {
            setDetailsVisibleOnClick(false)
            setSelectionMode(SelectionMode.NONE)

            addComponentColumn { ban -> createDetailsChevron(isExpanded = ban == expandedItem) }
                .setHeader("")
                .setWidth("3rem")
                .setFlexGrow(0)
                .setSortable(false)

            addColumn { ban -> getLevelLabel(ban.level) }
                .setHeader(getTranslation("ucp.banHistory.column.level"))
                .setWidth("8rem")
                .setFlexGrow(0)
                .setSortable(false)

            addComponentColumn { ban -> createReasonCell(ban.reason) }
                .setHeader(getTranslation("ucp.banHistory.column.reason"))
                .setWidth("18rem")
                .setFlexGrow(0)
                .setSortable(false)

            addColumn { ban -> getDurationLabel(ban) }
                .setHeader(getTranslation("ucp.banHistory.column.duration"))
                .setWidth("12rem")
                .setFlexGrow(0)
                .setSortable(false)

            addColumn { ban -> formatDateTime(ban.createTime) }
                .setHeader(getTranslation("ucp.banHistory.column.dateIssued"))
                .setWidth("14rem")
                .setFlexGrow(0)
                .setSortable(true)
                .setComparator(UcpBanHistoryEntry::createTime)

            addColumn { ban -> getStatusLabel(ban.status) }
                .setHeader(getTranslation("ucp.banHistory.column.status"))
                .setWidth("8rem")
                .setFlexGrow(0)
                .setSortable(false)

            setItemDetailsRenderer(ComponentRenderer { ban -> createBanDetails(ban) })

            addItemClickListener { event ->
                val clickedItem = event.item
                val previouslyExpandedItem = expandedItem

                if (clickedItem == previouslyExpandedItem) {
                    expandedItem = null
                    setDetailsVisible(clickedItem, false)
                    dataProvider.refreshItem(clickedItem)
                } else {
                    expandedItem = clickedItem

                    if (previouslyExpandedItem != null) {
                        setDetailsVisible(previouslyExpandedItem, false)
                        dataProvider.refreshItem(previouslyExpandedItem)
                    }
                    setDetailsVisible(clickedItem, true)
                    dataProvider.refreshItem(clickedItem)
                }
            }

            setEmptyStateText(emptyStateText)
            width = GRID_WIDTH
            maxWidth = "100%"
        }
    }

    private fun createDetailsChevron(isExpanded: Boolean): Component {
        val chevron = Span().apply {
            style.set("display", "block")
            style.set("width", "0.45rem")
            style.set("height", "0.45rem")
            style.set("border-right", "2px solid var(--lumo-secondary-text-color)")
            style.set("border-bottom", "2px solid var(--lumo-secondary-text-color)")
            style.set("transform", if (isExpanded) "rotate(225deg)" else "rotate(45deg)")
            style.set("transition", "transform 120ms ease")
            style.set("pointer-events", "none")
            element.setAttribute("aria-hidden", "true")
        }

        return Div(chevron).apply {
            style.set("display", "flex")
            style.set("align-items", "center")
            style.set("justify-content", "center")
            style.set("width", "1.5rem")
            style.set("height", "1.5rem")
            style.set("pointer-events", "none")
            style.set("user-select", "none")
        }
    }

    private fun createReasonCell(reason: String): Component {
        return Span(reason).apply {
            style.set("display", "block")
            style.set("width", "100%")
            style.set("overflow", "hidden")
            style.set("text-overflow", "ellipsis")
            style.set("white-space", "nowrap")
            element.setAttribute("title", reason)
        }
    }

    private fun createBanDetails(ban: UcpBanHistoryEntry): Component {
        val accentColor = when (ban.status) {
            UcpBanStatus.ACTIVE -> "var(--lumo-error-color)"
            UcpBanStatus.REVOKED -> "var(--lumo-success-color)"
            UcpBanStatus.EXPIRED -> "var(--lumo-contrast-40pct)"
        }

        val header = Span("${getLevelLabel(ban.level)} \u00b7 ${getStatusLabel(ban.status)}").apply {
            style.set("display", "block")
            style.set("font-weight", "600")
            style.set("font-size", "var(--lumo-font-size-l)")
            style.set("color", "var(--lumo-header-text-color)")
            style.set("margin-bottom", "var(--lumo-space-m)")
        }

        val detailsContent = Div().apply {
            add(
                createDetailRow(getTranslation("ucp.banHistory.details.reason"), ban.reason, allowWrapping = true),
                createDetailRow(getTranslation("ucp.banHistory.details.issued"), formatDateTime(ban.createTime)),
                createDetailRow(
                    getTranslation("ucp.banHistory.details.ends"),
                    ban.expiresAt?.let(::formatDateTime) ?: getTranslation("ucp.banHistory.duration.permanent"),
                ),
            )

            ban.revokeTime?.let { revokeTime ->
                add(createDetailRow(getTranslation("ucp.banHistory.details.revokedAt"), formatDateTime(revokeTime)))
            }

            ban.revokeReason?.takeIf(String::isNotBlank)?.let { revokeReason ->
                add(
                    createDetailRow(
                        getTranslation("ucp.banHistory.details.revokeReason"),
                        revokeReason,
                        allowWrapping = true,
                    ),
                )
            }
        }

        return Div(header, detailsContent).apply {
            width = DETAILS_CARD_WIDTH
            maxWidth = "calc(100% - 2rem)"
            style.set("box-sizing", "border-box")
            style.set("padding", "var(--lumo-space-m)")
            style.set("margin", "var(--lumo-space-xs) 0 var(--lumo-space-xs) 3rem")
            style.set("background", "var(--lumo-contrast-5pct)")
            style.set("border-left", "4px solid $accentColor")
            style.set("border-radius", "var(--lumo-border-radius-m)")
        }
    }

    private fun createDetailRow(label: String, value: String, allowWrapping: Boolean = false): Component {
        val labelComponent = Span(label).apply {
            style.set("display", "block")
            style.set("font-weight", "600")
            style.set("font-size", "var(--lumo-font-size-s)")
            style.set("color", "var(--lumo-secondary-text-color)")
            style.set("white-space", "nowrap")
            style.set("margin-bottom", "0.15rem")
        }

        val valueComponent = Span(value).apply {
            style.set("display", "block")
            style.set("font-size", "var(--lumo-font-size-m)")
            style.set("color", "var(--lumo-body-text-color)")
            if (allowWrapping) {
                style.set("white-space", "pre-wrap")
                style.set("overflow-wrap", "anywhere")
            } else {
                style.set("white-space", "nowrap")
            }
        }

        return Div(labelComponent, valueComponent).apply {
            style.set("padding", "0.35rem 0")
            style.set("margin-bottom", "0.15rem")
        }
    }

    private fun getDurationLabel(ban: UcpBanHistoryEntry): String {
        val expiresAt = ban.expiresAt ?: return getTranslation("ucp.banHistory.duration.permanent")
        val days = max(1, Duration.between(ban.createTime, expiresAt).toDays())
        return getTranslation("ucp.banHistory.duration.temporary", days)
    }

    private fun formatDateTime(dateTime: OffsetDateTime): String = dateTime.format(dateFormatter)

    private fun getLevelLabel(level: BanLevel): String = when (level) {
        BanLevel.GLOBAL -> getTranslation("ucp.banHistory.level.global")
        BanLevel.CHAT -> getTranslation("ucp.banHistory.level.chat")
        BanLevel.VAULT -> getTranslation("ucp.banHistory.level.vault")
    }

    private fun getStatusLabel(status: UcpBanStatus): String = when (status) {
        UcpBanStatus.ACTIVE -> getTranslation("ucp.banHistory.status.active")
        UcpBanStatus.EXPIRED -> getTranslation("ucp.banHistory.status.expired")
        UcpBanStatus.REVOKED -> getTranslation("ucp.banHistory.status.revoked")
    }
}
