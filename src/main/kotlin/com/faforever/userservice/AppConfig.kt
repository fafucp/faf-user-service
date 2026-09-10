package com.faforever.userservice

import com.faforever.userservice.ui.view.ucp.UcpConfirmAccountDeletionView
import com.faforever.userservice.ui.view.ucp.UcpDeleteAccountView
import com.vaadin.flow.component.dependency.StyleSheet
import com.vaadin.flow.component.page.AppShellConfigurator
import com.vaadin.flow.server.LoadDependenciesOnStartup
import com.vaadin.flow.theme.lumo.Lumo
import jakarta.enterprise.context.ApplicationScoped

@LoadDependenciesOnStartup(
    UcpDeleteAccountView::class,
    UcpConfirmAccountDeletionView::class,
)
@StyleSheet("styles.css")
@StyleSheet(Lumo.STYLESHEET)
@ApplicationScoped
class AppConfig : AppShellConfigurator
