package dev.claudiverse.app.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.claudiverse.app.AppContainer
import dev.claudiverse.core.ApiException
import dev.claudiverse.core.Host
import dev.claudiverse.core.Profile
import dev.claudiverse.core.ProfileFields
import dev.claudiverse.core.ProfileSave
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** The profile list: core's live store, kept running while the screen's view model lives. */
class ProfilesViewModel(private val app: AppContainer) : ViewModel() {
    val profiles: StateFlow<List<Profile>> = app.profiles.all

    init {
        app.watchProfiles()
    }

    fun refresh() {
        viewModelScope.launch { app.profiles.refresh() }
    }

    override fun onCleared() = app.unwatchProfiles()
}

data class ProfileEditorState(
    /** Null while creating a new profile. */
    val id: Long? = null,
    /** The profile as the server has it now: status, last start, seat. */
    val profile: Profile? = null,
    val name: String = "",
    /** What the edit started from: the fields as last read or saved. */
    val base: ProfileFields = ProfileFields(machine = "", folder = ""),
    val baseRevision: Long = 0,
    val draft: ProfileFields = ProfileFields(machine = "", folder = ""),
    /** After a 409: the server's fields, and the ones both sides changed. */
    val theirs: ProfileFields? = null,
    val conflicts: List<String> = emptyList(),
    /** Server field names to their messages, from a 422. */
    val errors: Map<String, List<String>> = emptyMap(),
    val hosts: List<Host> = emptyList(),
    val busy: Boolean = false,
    val notice: String? = null,
    /** Deleted, here or elsewhere: the screen closes. */
    val gone: Boolean = false,
) {
    val isNew: Boolean get() = id == null
    val edited: Boolean get() = isNew || draft != base
    val host: Host? get() = hosts.firstOrNull { it.name == draft.machine }
}

/**
 * One profile's editor and controls, or a new profile's form. Saves need the
 * super token; start, stop and restart take the app token too.
 */
class ProfileEditorViewModel(private val app: AppContainer, id: Long?) : ViewModel() {
    private val state = MutableStateFlow(ProfileEditorState(id = id))
    val ui: StateFlow<ProfileEditorState> = state.asStateFlow()

    init {
        app.watchProfiles()
        loadHosts()
        if (id != null) viewModelScope.launch {
            var seen = false
            app.profiles.all.collect { all ->
                val p = all.firstOrNull { it.id == id }
                when {
                    p == null && seen -> state.update { it.copy(gone = true) }
                    p == null -> Unit
                    else -> {
                        seen = true
                        state.update { s ->
                            // An unedited form follows the server; an edited one keeps
                            // the user's work, and a save then meets the 409 and merges.
                            if (s.profile == null || (s.draft == s.base && p.revision > s.baseRevision)) {
                                s.copy(profile = p, name = p.name, base = p.fields, draft = p.fields, baseRevision = p.revision)
                            } else s.copy(profile = p)
                        }
                    }
                }
            }
        }
    }

    fun loadHosts() {
        viewModelScope.launch {
            runCatching { app.api.hosts() }.onSuccess { h -> state.update { it.copy(hosts = h) } }
        }
    }

    fun setName(name: String) = state.update { it.copy(name = name, errors = it.errors - "name") }

    /** Change the draft; the field's error goes, since it may no longer hold. */
    fun edit(field: String, change: (ProfileFields) -> ProfileFields) =
        state.update { it.copy(draft = change(it.draft), errors = it.errors - field) }

    fun revert() = state.update { it.copy(draft = it.base, theirs = null, conflicts = emptyList(), errors = emptyMap(), notice = null) }

    /** Keep the other edit for [field], dropping this one. */
    fun takeTheirs(field: String) = state.update { s ->
        val theirs = s.theirs ?: return@update s
        val merged = JsonObject(s.draft.toJson() + (field to (theirs.toJson()[field] ?: JsonNull)))
        s.copy(draft = ProfileFields.from(merged), conflicts = s.conflicts - field)
    }

    /** Create or save; [onCreated] gets a new profile's id. */
    fun save(onCreated: (Long) -> Unit = {}) {
        val s = state.value
        state.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            val result = runCatching {
                if (s.isNew) app.api.createProfile(s.name.trim(), s.draft)
                else app.api.updateProfile(s.id!!, s.baseRevision, s.draft)
            }
            result.onFailure { e -> state.update { it.copy(busy = false, notice = refusal(e, "Not saved")) } }
            when (val r = result.getOrNull() ?: return@launch) {
                is ProfileSave.Saved -> {
                    app.profiles.put(r.profile)
                    state.update {
                        it.copy(
                            busy = false, profile = r.profile, base = r.profile.fields, draft = r.profile.fields,
                            baseRevision = r.profile.revision, theirs = null, conflicts = emptyList(), errors = emptyMap(),
                            notice = if (r.profile.restartNeeded) "Saved. Its seat runs the old settings: restart to apply." else "Saved",
                        )
                    }
                    if (s.isNew) onCreated(r.profile.id)
                }
                is ProfileSave.Invalid -> state.update { it.copy(busy = false, errors = r.errors, notice = "Not saved: see the marked fields") }
                is ProfileSave.Conflict -> {
                    val merge = ProfileFields.merge(s.base, s.draft, r.current.fields)
                    state.update {
                        it.copy(
                            busy = false, profile = r.current, base = r.current.fields, baseRevision = r.current.revision,
                            draft = merge.fields, theirs = r.current.fields, conflicts = merge.conflicts,
                            notice = if (merge.conflicts.isEmpty()) "Changed elsewhere too; both edits are in. Save again to keep them."
                            else "Changed elsewhere too; review the marked fields, then save again.",
                        )
                    }
                }
            }
        }
    }

    fun delete() {
        val id = state.value.id ?: return
        state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching { app.api.deleteProfile(id) }
                .onSuccess { state.update { it.copy(busy = false, gone = true) } }
                .onFailure { e -> state.update { it.copy(busy = false, notice = refusal(e, "Not deleted")) } }
        }
    }

    fun control(action: String) {
        val id = state.value.id ?: return
        state.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            runCatching {
                when (action) {
                    "start" -> app.profiles.put(app.api.startProfile(id))
                    "restart" -> app.profiles.put(app.api.restartProfile(id))
                    else -> app.api.stopProfile(id)
                }
            }
                .onSuccess { state.update { it.copy(busy = false) }; app.profiles.refresh() }
                .onFailure { e ->
                    val what = when (action) { "start" -> "Not started"; "restart" -> "Not restarted"; else -> "Not stopped" }
                    state.update { it.copy(busy = false, notice = refusal(e, what)) }
                }
        }
    }

    private fun refusal(e: Throwable, what: String): String {
        val ex = e as? ApiException
        return when (ex?.httpStatus) {
            403 -> "$what: this needs a signed-in token (Connection → Account → Sign in)"
            409 -> "$what: ${ex.message?.substringAfter(": ")}"
            504 -> "$what: the machine did not answer"
            else -> "$what: ${e.message}"
        }
    }

    override fun onCleared() = app.unwatchProfiles()
}
