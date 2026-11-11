package io.delightlabs.biometricslib.internal.prompt

import io.delightlabs.biometricslib.internal.session.AuthenticationSession

/** Keeps a restart delay on the host's main queue until the request is handed to AndroidX. */
internal class AuthenticationLaunchQueue(
    private val session: AuthenticationSession,
    private val restartDelayMillis: Long,
    private val uptimeMillis: () -> Long,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
    private val onAbandoned: (AuthenticationSession.Request) -> Unit
) {
    private var readyAtMillis = 0L
    private var pending: PendingLaunch? = null

    fun recordPlatformCompletion() {
        readyAtMillis = uptimeMillis() + restartDelayMillis
    }

    fun launch(
        request: AuthenticationSession.Request,
        isCallerActive: () -> Boolean,
        launchPrompt: () -> Unit
    ) {
        val waitMillis = readyAtMillis - uptimeMillis()
        if (waitMillis <= 0L) {
            dispatch(request, isCallerActive, launchPrompt)
            return
        }

        val task = Runnable {
            if (isPending(request)) {
                pending = null
                dispatch(request, isCallerActive, launchPrompt)
            }
        }
        pending = PendingLaunch(request, task)
        postDelayed(task, waitMillis)
    }

    fun isPending(request: AuthenticationSession.Request): Boolean = pending?.request === request

    fun cancelPending(request: AuthenticationSession.Request): Boolean {
        if (!removePending(request)) return false
        abandon(request)
        return true
    }

    fun removePending(request: AuthenticationSession.Request): Boolean {
        if (!isPending(request)) return false
        clear()
        return true
    }

    fun clear() {
        val task = pending?.task ?: return
        pending = null
        removeCallbacks(task)
    }

    private fun dispatch(
        request: AuthenticationSession.Request,
        isCallerActive: () -> Boolean,
        launchPrompt: () -> Unit
    ) {
        if (session.active !== request) return
        // The coroutine can be cancelled before its cleanup message reaches the main queue.
        if (isCallerActive()) launchPrompt() else abandon(request)
    }

    private fun abandon(request: AuthenticationSession.Request) {
        session.abandonBeforeLaunch(request)
        onAbandoned(request)
    }

    private data class PendingLaunch(
        val request: AuthenticationSession.Request,
        val task: Runnable
    )
}
