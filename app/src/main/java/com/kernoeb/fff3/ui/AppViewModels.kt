package com.kernoeb.fff3.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kernoeb.fff3.AppContainer
import com.kernoeb.fff3.appContainer

/**
 * Gets or creates a ViewModel built from the [AppContainer]. Inside a navigation entry it is scoped
 * to that entry. Example: `val vm = appViewModel { HomeViewModel(it.repository, it.controller) }`.
 */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    key: String? = null,
    crossinline create: (AppContainer) -> VM,
): VM {
    val container = LocalContext.current.appContainer
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}
