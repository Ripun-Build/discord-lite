package com.discordlite.app.ui.friends

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.discordlite.app.data.SocialRepository
import com.discordlite.app.data.remote.PublicUser
import com.discordlite.app.data.remote.Relationship
import com.discordlite.app.ui.auth.friendlyMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class FriendsViewModel(private val social: SocialRepository) : ViewModel() {
    var query by mutableStateOf("")
        private set
    var results by mutableStateOf<List<PublicUser>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set
    /** Users with an action in flight, so their row shows a spinner. */
    var pending by mutableStateOf<Set<String>>(emptySet())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var errorTick by mutableIntStateOf(0)
        private set

    private var searchJob: Job? = null

    fun refresh() {
        viewModelScope.launch { runCatching { social.refresh() }.onFailure(::fail) }
    }

    /** Debounced search: waits for a short pause in typing before asking the server. */
    fun onQueryChange(value: String) {
        query = value.lowercase().take(21)
        error = null
        searchJob?.cancel()
        val q = query.trim().removePrefix("@")
        if (q.length < 2) {
            results = emptyList()
            searching = false
            return
        }
        searching = true
        searchJob = viewModelScope.launch {
            delay(300)
            runCatching { social.search(q) }
                .onSuccess { results = it }
                .onFailure(::fail)
            searching = false
        }
    }

    /** The one button on a search result: add, cancel, or accept depending on where you stand. */
    fun act(user: PublicUser) = mutate(user.id) {
        val next = when (user.relationship) {
            Relationship.NONE -> social.sendRequest(user.id).let { if (social.friends.value.friends.any { it.id == user.id }) Relationship.FRIEND else Relationship.OUTGOING }
            Relationship.OUTGOING -> social.removeRequest(user.id).let { Relationship.NONE }
            Relationship.INCOMING -> social.accept(user.id).let { Relationship.FRIEND }
            Relationship.FRIEND -> Relationship.FRIEND
        }
        results = results.map { if (it.id == user.id) it.copy(relationship = next) else it }
    }

    fun accept(userId: String) = mutate(userId) { social.accept(userId) }
    fun decline(userId: String) = mutate(userId) { social.removeRequest(userId) }
    fun cancel(userId: String) = mutate(userId) { social.removeRequest(userId) }
    fun unfriend(userId: String) = mutate(userId) { social.unfriend(userId) }
    fun block(userId: String) = mutate(userId) {
        social.block(userId)
        results = results.filterNot { it.id == userId }
    }

    private fun mutate(userId: String, block: suspend () -> Unit) {
        if (userId in pending) return
        pending = pending + userId
        viewModelScope.launch {
            runCatching { block() }.onFailure(::fail)
            pending = pending - userId
        }
    }

    private fun fail(e: Throwable) {
        error = friendlyMessage(e)
        errorTick++
    }
}
