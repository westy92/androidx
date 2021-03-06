/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.wear.watchface

import android.content.ComponentName
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.icu.util.Calendar
import android.support.wearable.complications.ComplicationData
import androidx.wear.complications.SystemProviders
import androidx.wear.complications.rendering.ComplicationDrawable

/**
 * Complications use a ComplicationBoundsProvider to compute their bounds.
 */
interface ComplicationBoundsProvider {
    /**
     * Computes the screen space bounds of the complication. This can be animated.
     *
     * @param complication The {@link Complication} to compute bounds for
     * @param screen A {@link Rect} describing the bounds of the screen
     */
    fun computeBounds(complication: Complication, screen: Rect): Rect
}

/**
 * A {@link ComplicationBoundsProvider} for watch faces that wish to have a full screen user
 * selectable backdrop. This sort of complication isn't clickable and at most one may be present in
 * the list of complications.
 */
class BackgroundComplicationBoundsProvider : ComplicationBoundsProvider {
    /** {@inheritDoc} */
    override fun computeBounds(
        complication: Complication,
        screen: Rect
    ) = screen // A BackgroundComplication covers the whole screen.
}

/**
 * A {@link ComplicationBoundsProvider} which converts bounds provided in the unit square [0..1] to
 * device pixels. NB 0 and 1 are included in the unit square.
 */
class UnitSquareBoundsProvider(
    /**
     * Fractional bounds for the complication (clamped to the unit square.) which get converted
     * to screen space coordinates.
     */
    unitSquareBounds: RectF
) : ComplicationBoundsProvider {

    private companion object {
        private val unitSquare = RectF(0f, 0f, 1f, 1f)
    }

    private val clampedUnitSquareBounds = RectF().apply {
        setIntersect(
            unitSquareBounds,
            unitSquare
        )
    }

    /** {@inheritDoc} */
    override fun computeBounds(complication: Complication, screen: Rect) =
        Rect(
            (clampedUnitSquareBounds.left * screen.width()).toInt(),
            (clampedUnitSquareBounds.top * screen.height()).toInt(),
            (clampedUnitSquareBounds.right * screen.width()).toInt(),
            (clampedUnitSquareBounds.bottom * screen.height()).toInt()
        )
}

/** Common interface for rendering complications. */
interface ComplicationRenderer {
    /**
     * Called when the ComplicationRenderer attaches to a {@link Complication}.
     */
    fun onAttach()

    /**
     * Called when the ComplicationRenderer detaches from a {@link Complication}.
     */
    fun onDetach()

    /**
     * Draws the complication into the canvas with the specified bounds. This will usually be
     * called by user watch face drawing code, but the system may also call it for complication
     * selection UI rendering. The width and height will be the same as that computed by
     * computeBounds but the translation and canvas size may differ.
     *
     * @param canvas The {@link Canvas} to render into
     * @param bounds A {@link Rect} describing the bounds of the complication
     * @param calendar The current {@link Calendar}
     * @param drawMode The current {@link DrawMode}
     */
    fun onDraw(
        canvas: Canvas,
        bounds: Rect,
        calendar: Calendar,
        @DrawMode drawMode: Int
    )

    /**
     * Sets whether the complication should be drawn highlighted. This is to provide visual
     * feedback when the user taps on a complication.
     *
     * @param highlight Whether or not the complication should be drawn highlighted.
     */
    fun setIsHighlighted(highlight: Boolean)

    /**
     * Sets the current {@link ComplicationData}.
     *
     * @param data The {@link ComplicationData}
     */
    fun setData(data: ComplicationData?)

    /**
     * Returns the current {@link ComplicationData} associated with the ComplicationRenderer.
     */
    fun getData(): ComplicationData?

    interface InvalidateCallback {
        /** Requests redraw. */
        fun invalidate()
    }

    /**
     * Called by the {@link WatchFace}
     *
     * @param callback The {@link InvalidateCallback} to register
     */
    fun setInvalidateCallback(callback: InvalidateCallback)
}

/**
 * A complication rendered with ComplicationDrawable which does a lot of hard work for you. This
 * renderer can't be shared by multiple complications.
 */
open class ComplicationDrawableRenderer(
    /** The actual complication. */
    protected val drawable: ComplicationDrawable,

    private val watchState: WatchState
) : ComplicationRenderer {

    private inner class SystemStateListener : WatchState.Listener {
        override fun onAmbientModeChanged(isAmbient: Boolean) {
            drawable.setInAmbientMode(isAmbient)
        }
    }

    private val systemStateListener = SystemStateListener()

    private var complicationData: ComplicationData? = null

    /** {@inheritDoc} */
    override fun onAttach() {
        drawable.setInAmbientMode(watchState.isAmbient)
        drawable.setLowBitAmbient(watchState.hasLowBitAmbient)
        drawable.setBurnInProtection(watchState.hasBurnInProtection)

        watchState.addListener(systemStateListener)
    }

    /** {@inheritDoc} */
    override fun onDetach() {
        watchState.removeListener(systemStateListener)
    }

    /** {@inheritDoc} */
    override fun onDraw(
        canvas: Canvas,
        bounds: Rect,
        calendar: Calendar,
        @DrawMode drawMode: Int
    ) {
        drawable.bounds = bounds
        drawable.draw(
            canvas,
            calendar.timeInMillis
        )
    }

    /** {@inheritDoc} */
    override fun setIsHighlighted(highlight: Boolean) {
        drawable.highlighted = highlight
    }

    /** {@inheritDoc} */
    override fun setData(data: ComplicationData?) {
        drawable.setComplicationData(data)
        complicationData = data
    }

    /** {@inheritDoc} */
    override fun getData() = complicationData

    /** {@inheritDoc} */
    override fun setInvalidateCallback(callback: ComplicationRenderer.InvalidateCallback) {
        drawable.callback = object :
            Drawable.Callback {
            override fun unscheduleDrawable(who: Drawable, what: Runnable) {}

            override fun invalidateDrawable(who: Drawable) {
                callback.invalidate()
            }

            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {}
        }
    }
}

/**
 * Represents a individual complication on the screen. The number of complications is fixed
 * (see {@link ComplicationSet}) but complications can be enabled or disabled as needed.
 */
class Complication @JvmOverloads constructor(
    /** The watch face's ID for this complication. */
    internal val id: Int,

    /**  An interface that can provide the bounds for this Complication. */
    val boundsProvider: ComplicationBoundsProvider,

    /**
     * The renderer for this Complication. Renderers may not be sharable between complications.
     */
    internal var renderer: ComplicationRenderer,

    /**
     * The types of complication supported by this Complication. Passed into {@link
     * ComplicationHelperActivity#createProviderChooserHelperIntent} during complication
     * configuration.
     */
    internal val supportedTypes: IntArray,

    /** Default complication provider. */
    internal val defaultProvider: DefaultComplicationProvider,

    /** Default complication provider data type. */
    internal val defaultProviderType: Int = WatchFace.DEFAULT_PROVIDER_TYPE_NONE
) {
    init {
        renderer.onAttach()
    }

    /**
     * A watch face may wish to try and set one or more non-system providers as the default provider
     * for a complication. If a provider can't be used for some reason (e.g. it isn't installed or
     * it doesn't support the requested type, or the watch face lacks the necessary permission)
     * then the next one will be tried. A system provider acts as a final fallback in case no
     * non-system providers can be used.
     *
     * If the DefaultComplicationProvider is empty then no default is set.
     */
    class DefaultComplicationProvider(
        /** List of up to two non-system providers to be tried in turn. This may be empty. */
        val providers: List<ComponentName> = listOf(),

        /** Fallback in case none of the non-system providers could be used. */
        @SystemProviders.ProviderId val systemProviderFallback: Int = WatchFace.NO_DEFAULT_PROVIDER
    ) {
        constructor(systemProviderFallback: Int) : this(listOf(), systemProviderFallback)

        fun isEmpty() =
            providers.isEmpty() && systemProviderFallback == WatchFace.NO_DEFAULT_PROVIDER
    }

    private lateinit var complicationSet: ComplicationSet
    private lateinit var invalidateCallback: ComplicationRenderer.InvalidateCallback
    private var _enabled = true

    var enabled: Boolean
        get() = _enabled
        set(value) {
            _enabled = value

            // The caller might enable/disable a number of complications. For efficiency we need
            // to coalesce these into one update task.
            complicationSet.scheduleUpdateActiveComplications()
        }

    /** Any data for the complication. */
    private var _data: ComplicationData? = null

    var data: ComplicationData?
        get() = _data
        internal set(value) {
            _data = value
            renderer.setData(_data)

            // In tests this may not be initialized.
            if (this::complicationSet.isInitialized) {
                // Update active complications to ensure accessibility data is up to date.
                complicationSet.scheduleUpdateActiveComplications()
            }
        }

    /**
     * Sets the current {@link ComplicationRenderer}. This is useful if based on the style the
     * watch face needs to use a different renderer.
     */
    fun setRenderer(renderer: ComplicationRenderer) {
        renderer.onDetach()
        this.renderer = renderer
        renderer.onAttach()
        initRenderer()
    }

    /**
     * Watch faces should use this method to render a complication. Note the system may call this.
     *
     * @param canvas The {@link Canvas} to render into
     * @param calendar The current {@link Calendar}
     * @param drawMode The current {@link DrawMode}
     */
    fun draw(
        canvas: Canvas,
        calendar: Calendar,
        @DrawMode drawMode: Int
    ) {
        val bounds = boundsProvider.computeBounds(
            this,
            Rect(0, 0, canvas.width, canvas.height)
        )
        renderer.onDraw(canvas, bounds, calendar, drawMode)
    }

    /**
     * Sets whether the complication should be drawn highlighted or not. This is to provide visual
     * feedback when the user taps on a complication.
     *
     * @param highlight Whether or not the complication should be drawn highlighted.
     */
    internal fun setIsHighlighted(highlight: Boolean) {
        renderer.setIsHighlighted(highlight)
    }

    private fun initRenderer() {
        renderer.setData(data)

        // Renderers may register a user style listener during their initializer which can call
        // setComplicationRenderer() before complicationInvalidateCallback has been initialized.
        if (this::invalidateCallback.isInitialized) {
            renderer.setInvalidateCallback(invalidateCallback)
        }
    }

    internal fun init(
        complicationSet: ComplicationSet,
        invalidateCallback: ComplicationRenderer.InvalidateCallback
    ) {
        this.complicationSet = complicationSet
        this.invalidateCallback = invalidateCallback
        initRenderer()
    }
}
