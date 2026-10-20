package com.shilapi.xcertplay.update

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.shilapi.xcertplay.DiPlayActivity
import com.shilapi.xcertplay.ExperimentalFeatures
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The update card in the other half of the app's language setting, from the same resources. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN")
class UpdateCardChineseUiTest {

    @Before fun thisPackageHasTheBucket() = ExperimentalFeatures.override(true)

    @After fun giveThePackageItsOwnAnswerBack() = ExperimentalFeatures.resetOverride()

    @Test
    fun theCardIsInChineseOnTheChinesePage() {
        val page = Robolectric.buildActivity(
            DiPlayActivity::class.java,
            Intent(RuntimeEnvironment.getApplication(), DiPlayActivity::class.java).putExtra("page", "settings"),
        ).setup().get()

        val texts = mutableListOf<String>()
        val buttons = mutableListOf<String>()
        fun walk(view: View) {
            if (view is TextView) view.text?.toString()?.let { texts += it }
            if (view is Button) buttons += view.text.toString()
            if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
        walk(page.findViewById(android.R.id.content))

        assertTrue("the card's title is translated", texts.contains("软件更新"))
        assertTrue("so is its description", texts.any { it.startsWith("只在按下按钮时检查官方版本") })
        assertTrue("so is the version line", texts.any { it.startsWith("当前版本：") })
        assertEquals("and the button", listOf("检查更新"), buttons.filter { it == "检查更新" })
    }
}
