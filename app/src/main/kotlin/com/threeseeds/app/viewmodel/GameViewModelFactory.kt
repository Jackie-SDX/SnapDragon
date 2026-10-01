package com.threeseeds.app.viewmodel

import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.savedstate.SavedStateRegistryOwner
import com.threeseeds.app.audio.SoundPlayer
import com.threeseeds.app.profile.ProfileStore
import com.threeseeds.app.settings.SettingsStore

class GameViewModelFactory(
    owner: SavedStateRegistryOwner,
    private val settings: SettingsStore,
    private val soundPlayer: SoundPlayer,
    private val profile: ProfileStore,
    private val localName: () -> String = { "Player" }
) : AbstractSavedStateViewModelFactory(owner, null) {

    override fun <T : ViewModel> create(
        key: String,
        modelClass: Class<T>,
        handle: SavedStateHandle
    ): T {
        @Suppress("UNCHECKED_CAST")
        return GameViewModel(handle, settings, soundPlayer, profile, localName = localName) as T
    }
}
