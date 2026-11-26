package com.guiamed.ui

import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import com.guiamed.R

internal class ReadingPanel(private val scroll: ScrollView) {
    val content: View = scroll.findViewById(R.id.topPanel)
    private val heading: TextView = content.findViewById(R.id.statusText)
    private val alertLabel: TextView = content.findViewById(R.id.alertLabel)
    private val details: TextView = content.findViewById(R.id.readingDetails)
    private val identity: TextView = content.findViewById(R.id.appIdentity)
    private val accent: View = content.findViewById(R.id.statusAccent)
    private val divider: View = content.findViewById(R.id.statusDivider)

    init {
        ViewCompat.setAccessibilityHeading(heading, true)
    }

    fun show(title: String, detail: String = "", alert: String? = null) {
        val context = content.context
        val isAlert = alert != null
        val foregroundColor = ContextCompat.getColor(
            context,
            if (isAlert) R.color.black else R.color.yellow_high_contrast,
        )
        content.setBackgroundColor(ContextCompat.getColor(context, if (isAlert) R.color.red_danger else R.color.black))
        heading.setTextAppearance(
            if (!isAlert) R.style.TextAppearance_GuiaMed_HighContrast else R.style.TextAppearance_GuiaMed_Danger,
        )
        identity.setTextColor(foregroundColor)
        accent.setBackgroundColor(foregroundColor)
        divider.setBackgroundColor(foregroundColor)
        details.setTextColor(ContextCompat.getColor(context, if (!isAlert) R.color.white else R.color.black))
        alertLabel.text = alert
        alertLabel.isVisible = alert != null
        heading.text = title
        showDetails(detail)
    }

    fun show(result: ReadingPresentation) = show(result.title, result.details, result.alert)

    fun showDetails(text: String) {
        details.text = text
        details.isVisible = text.isNotBlank()
        divider.isVisible = text.isNotBlank()
        scroll.scrollTo(0, 0)
    }
}
