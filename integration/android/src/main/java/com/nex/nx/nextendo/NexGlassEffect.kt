package com.nex.nx.nextendo

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.navigation.NavigationBarView

/**
 * Android-native glass treatment for NEX surfaces.
 *
 * This deliberately uses translucent gradients and highlights rather than a
 * fake backdrop-blur API: it works on Android 11 and newer, including devices
 * without Android 12's window blur support.
 */
object NexGlassEffect {
    private val checkedState = intArrayOf(android.R.attr.state_checked)
    private val uncheckedState = intArrayOf()

    fun apply(root: View) {
        applyRecursively(root)
    }

    private fun applyRecursively(view: View) {
        when (view) {
            is MaterialButton -> styleButton(view)
            is MaterialCardView -> if (isSurfaceCard(view)) styleCard(view)
            is SwitchCompat -> styleSwitch(view)
            is Switch -> stylePlatformSwitch(view)
            is Toolbar -> styleBar(view)
            is AppBarLayout -> styleBar(view)
            is NavigationBarView -> styleBar(view)
            is ViewGroup -> if (isNamedBar(view)) styleContainerBar(view)
        }

        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                applyRecursively(view.getChildAt(index))
            }
        }
    }

    private fun styleButton(button: MaterialButton) {
        val surface = color(button, com.google.android.material.R.attr.colorSurface, 0xFF20242C.toInt())
        val primary = color(button, com.google.android.material.R.attr.colorPrimary, 0xFFE30613.toInt())
        button.backgroundTintList = ColorStateList.valueOf(withAlpha(surface, 0xE2))
        button.strokeColor = ColorStateList.valueOf(withAlpha(primary, 0xA0))
        button.strokeWidth = dp(button, 1)
        button.cornerRadius = dp(button, 14)
        button.rippleColor = ColorStateList.valueOf(withAlpha(primary, 0x32))
        button.elevation = 0f
    }

    private fun styleCard(card: MaterialCardView) {
        val surface = color(card, com.google.android.material.R.attr.colorSurface, 0xFF20242C.toInt())
        val primary = color(card, com.google.android.material.R.attr.colorPrimary, 0xFFE30613.toInt())
        card.setCardBackgroundColor(ColorStateList.valueOf(withAlpha(surface, 0xD8)))
        card.setStrokeColor(ColorStateList.valueOf(withAlpha(primary, 0x78)))
        card.strokeWidth = dp(card, 1)
        card.radius = dp(card, 16).toFloat()
        card.cardElevation = 0f
    }

    private fun styleSwitch(toggle: SwitchCompat) {
        val primary = color(toggle, com.google.android.material.R.attr.colorPrimary, 0xFFE30613.toInt())
        val outline = color(toggle, com.google.android.material.R.attr.colorOutline, 0xFF89909C.toInt())
        toggle.thumbTintList = ColorStateList(
            arrayOf(checkedState, uncheckedState),
            intArrayOf(primary, withAlpha(Color.WHITE, 0xF0))
        )
        toggle.trackTintList = ColorStateList(
            arrayOf(checkedState, uncheckedState),
            intArrayOf(withAlpha(primary, 0x90), withAlpha(outline, 0x90))
        )
        toggle.background = glassBackground(
            toggle,
            color(toggle, com.google.android.material.R.attr.colorSurface, 0xFF20242C.toInt()),
            primary,
            18
        )
    }

    private fun stylePlatformSwitch(toggle: Switch) {
        val primary = color(toggle, com.google.android.material.R.attr.colorPrimary, 0xFFE30613.toInt())
        val outline = color(toggle, com.google.android.material.R.attr.colorOutline, 0xFF89909C.toInt())
        toggle.thumbTintList = ColorStateList(
            arrayOf(checkedState, uncheckedState),
            intArrayOf(primary, withAlpha(Color.WHITE, 0xF0))
        )
        toggle.trackTintList = ColorStateList(
            arrayOf(checkedState, uncheckedState),
            intArrayOf(withAlpha(primary, 0x90), withAlpha(outline, 0x90))
        )
    }

    private fun styleBar(view: View) {
        val surface = color(view, com.google.android.material.R.attr.colorSurface, 0xFF20242C.toInt())
        val primary = color(view, com.google.android.material.R.attr.colorPrimary, 0xFFE30613.toInt())
        view.background = glassBackground(view, surface, primary, 16)
    }

    private fun styleContainerBar(view: ViewGroup) {
        val surface = color(view, com.google.android.material.R.attr.colorSurface, 0xFF20242C.toInt())
        val primary = color(view, com.google.android.material.R.attr.colorPrimary, 0xFFE30613.toInt())
        view.background = glassBackground(view, surface, primary, 16)
    }

    private fun isSurfaceCard(view: MaterialCardView): Boolean {
        val name = resourceName(view)
        return name.contains("button") ||
            name.contains("card") ||
            name.contains("search_background") ||
            name.contains("toolbar") ||
            name.contains("app_bar")
    }

    private fun isNamedBar(view: ViewGroup): Boolean {
        val name = resourceName(view)
        return name.contains("toolbar") ||
            name.contains("app_bar") ||
            name.contains("bottom_bar") ||
            name.contains("navigation_bar")
    }

    private fun resourceName(view: View): String =
        if (view.id == View.NO_ID) "" else
            runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")

    private fun glassBackground(
        view: View,
        surface: Int,
        primary: Int,
        radiusDp: Int
    ): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(withAlpha(surface, 0xE0), withAlpha(surface, 0xC8))
        ).apply {
            cornerRadius = dp(view, radiusDp).toFloat()
            setStroke(dp(view, 1), withAlpha(primary, 0x78))
        }
    }

    private fun color(view: View, attribute: Int, fallback: Int): Int =
        MaterialColors.getColor(view, attribute, fallback)

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)

    private fun dp(view: View, value: Int): Int =
        (value * view.resources.displayMetrics.density).toInt().coerceAtLeast(1)
}
