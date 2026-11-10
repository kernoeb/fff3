package com.kernoeb.fff3.data

import androidx.datastore.core.DataStore
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Devices, groups, favourites and settings. Survives restarts. */
interface LedRepository {

    /** Everything at once. Null until the file is read, which takes a few milliseconds at start. */
    val snapshot: StateFlow<AppData?>

    val devices: Flow<List<KnownDevice>>
    val groups: Flow<List<DeviceGroup>>
    val favourites: Flow<List<Favourite>>
    val settings: Flow<Settings>

    /** Adds the device, or replaces the one with the same address. */
    suspend fun saveDevice(device: KnownDevice)
    suspend fun renameDevice(address: String, label: String?)

    /** Also removes the device from every group. */
    suspend fun removeDevice(address: String)

    suspend fun createGroup(name: String, memberAddresses: List<String>): DeviceGroup
    suspend fun saveGroup(group: DeviceGroup)
    suspend fun deleteGroup(id: String)

    suspend fun saveFavourite(favourite: Favourite)
    suspend fun deleteFavourite(id: String)

    /** Puts favourites in the order of [ids]. Favourites missing from [ids] keep their place at the end. */
    suspend fun reorderFavourites(ids: List<String>)

    suspend fun updateSettings(transform: (Settings) -> Settings)
}

class DataStoreLedRepository(
    private val store: DataStore<AppData>,
    scope: CoroutineScope,
) : LedRepository {

    override val snapshot: StateFlow<AppData?> = store.data.stateIn(scope, SharingStarted.Eagerly, null)

    private val loaded = snapshot.filterNotNull()
    override val devices = loaded.map { it.devices }.distinctUntilChanged()
    override val groups = loaded.map { it.groups }.distinctUntilChanged()
    override val favourites = loaded.map { it.favourites }.distinctUntilChanged()
    override val settings = loaded.map { it.settings }.distinctUntilChanged()

    override suspend fun saveDevice(device: KnownDevice) {
        val normalised = device.copy(address = device.address.uppercase())
        update { data ->
            val others = data.devices.filterNot { it.address == normalised.address }
            data.copy(devices = others + normalised)
        }
    }

    override suspend fun renameDevice(address: String, label: String?) = update { data ->
        data.copy(devices = data.devices.map { if (it.address == address) it.copy(label = label?.trim()) else it })
    }

    override suspend fun removeDevice(address: String) = update { data ->
        data.copy(
            devices = data.devices.filterNot { it.address == address },
            groups = data.groups.map { group -> group.copy(memberAddresses = group.memberAddresses - address) },
            settings = data.settings.copy(lightDelaysMs = data.settings.lightDelaysMs - address),
        )
    }

    override suspend fun createGroup(name: String, memberAddresses: List<String>): DeviceGroup {
        val group = DeviceGroup(UUID.randomUUID().toString(), name.trim(), memberAddresses.distinct())
        update { it.copy(groups = it.groups + group) }
        return group
    }

    override suspend fun saveGroup(group: DeviceGroup) = update { data ->
        val exists = data.groups.any { it.id == group.id }
        data.copy(groups = if (exists) data.groups.map { if (it.id == group.id) group else it } else data.groups + group)
    }

    override suspend fun deleteGroup(id: String) = update { data ->
        data.copy(groups = data.groups.filterNot { it.id == id })
    }

    override suspend fun saveFavourite(favourite: Favourite) = update { data ->
        val exists = data.favourites.any { it.id == favourite.id }
        data.copy(
            favourites = if (exists) data.favourites.map { if (it.id == favourite.id) favourite else it }
            else data.favourites + favourite,
        )
    }

    override suspend fun deleteFavourite(id: String) = update { data ->
        data.copy(favourites = data.favourites.filterNot { it.id == id })
    }

    override suspend fun reorderFavourites(ids: List<String>) = update { data ->
        data.copy(favourites = data.favourites.inOrderOf(ids))
    }

    override suspend fun updateSettings(transform: (Settings) -> Settings) = update { data ->
        data.copy(settings = transform(data.settings))
    }

    private suspend fun update(transform: (AppData) -> AppData) {
        store.updateData(transform)
    }
}

/** Sorts by position in [ids]; items not in [ids] go last, in their current order. */
internal fun List<Favourite>.inOrderOf(ids: List<String>): List<Favourite> {
    val position = ids.withIndex().associate { (index, id) -> id to index }
    return sortedBy { position[it.id] ?: Int.MAX_VALUE }
}
