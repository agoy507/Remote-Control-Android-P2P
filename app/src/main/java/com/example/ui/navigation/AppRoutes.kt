package com.example.ui.navigation

import kotlinx.serialization.Serializable

sealed interface AppRoute {
    @Serializable
    data object Beranda : AppRoute

    @Serializable
    data object Kendali : AppRoute

    @Serializable
    data object ServerConfig : AppRoute

    @Serializable
    data object KanvasRemote : AppRoute
}
