package com.limelight.binding.input.virtual_controller.xstreaming

import android.app.Activity
import androidx.core.content.ContextCompat
import androidx.window.area.WindowAreaCapability
import androidx.window.area.WindowAreaController
import androidx.window.area.WindowAreaPresentationSessionCallback
import androidx.window.area.WindowAreaSessionPresenter
import androidx.window.core.ExperimentalWindowApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/**
 * Shows the XStreaming-style pad's cover-screen controls on a foldable's outer
 * display via Jetpack WindowManager's Dual Screen Mode
 * (WindowAreaController.presentContentOnWindowArea), while the game keeps
 * streaming on the inner display.
 *
 * Ported from XStreaming (com.xstreaming.CoverDisplayModule), with the React
 * Native bridge (a second ReactRootView, JS event emission) replaced by a
 * plain [XSCoverGamepadView] and a direct listener callback -- there is no JS
 * runtime on this side, so no cross-tree touch-responder collision to guard
 * against either.
 */
@OptIn(ExperimentalWindowApi::class)
class XSCoverDisplayController(private val activity: Activity) {

    interface Listener {
        fun onButtonStateChanged(buttonName: String, pressed: Boolean)

        /** Fires whenever the cover-display present capability becomes available/unavailable. */
        fun onAvailabilityChanged(available: Boolean)
    }

    private val controller: WindowAreaController by lazy { WindowAreaController.getOrCreate() }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val presentOp = WindowAreaCapability.Operation.OPERATION_PRESENT_ON_AREA

    private var presenter: WindowAreaSessionPresenter? = null
    private var coverView: XSCoverGamepadView? = null
    private var watching = false
    private var lastAvailable = false

    var listener: Listener? = null

    var layout: List<XSCoverButton> = emptyList()
        set(value) {
            field = value
            coverView?.setLayout(value)
        }

    /** True once a status change has told us a present-capable area currently exists. */
    fun isAvailable(): Boolean = lastAvailable

    /**
     * Starts observing capability changes (the foldable being opened/closed),
     * reporting each availability flip via [Listener.onAvailabilityChanged].
     * Safe to call more than once; only the first call does anything.
     */
    fun startWatching() {
        if (watching) {
            return
        }
        watching = true
        scope.launch {
            try {
                controller.windowAreaInfos.collect { infos ->
                    val available = infos.any {
                        it.getCapability(presentOp).status ==
                            WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE
                    }
                    if (available != lastAvailable) {
                        lastAvailable = available
                        listener?.onAvailabilityChanged(available)
                    }
                }
            } catch (ignored: Throwable) {
            }
        }
    }

    /** Presents the cover controls on the outer display, if a present-capable area exists. */
    fun present() {
        if (presenter != null) {
            return
        }
        scope.launch {
            val info = try {
                controller.windowAreaInfos.firstOrNull()?.firstOrNull {
                    it.getCapability(presentOp).status ==
                        WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE
                }
            } catch (e: Throwable) {
                null
            } ?: return@launch

            try {
                controller.presentContentOnWindowArea(
                    token = info.token,
                    activity = activity,
                    executor = ContextCompat.getMainExecutor(activity),
                    windowAreaPresentationSessionCallback =
                        object : WindowAreaPresentationSessionCallback {
                            override fun onSessionStarted(session: WindowAreaSessionPresenter) {
                                presenter = session
                                val view = XSCoverGamepadView(session.context)
                                view.setLayout(layout)
                                view.setListener(
                                    object : XSCoverGamepadView.Listener {
                                        override fun onButtonStateChanged(
                                            buttonName: String,
                                            pressed: Boolean,
                                        ) {
                                            listener?.onButtonStateChanged(buttonName, pressed)
                                        }
                                    })
                                coverView = view
                                session.setContentView(view)
                            }

                            override fun onSessionEnded(t: Throwable?) {
                                cleanup()
                            }

                            override fun onContainerVisibilityChanged(isVisible: Boolean) {
                                // Nothing to do: the pad itself doesn't need to react.
                            }
                        })
            } catch (ignored: Throwable) {
            }
        }
    }

    /** Closes the cover-display session, if one is open. Releasing any held button first. */
    fun dismiss() {
        coverView?.releaseAll()
        try {
            presenter?.close()
        } catch (ignored: Throwable) {
        }
        cleanup()
    }

    private fun cleanup() {
        coverView?.releaseAll()
        coverView = null
        presenter = null
    }

    /** Tears everything down; this controller cannot be reused afterwards. */
    fun destroy() {
        dismiss()
        scope.cancel()
    }
}
