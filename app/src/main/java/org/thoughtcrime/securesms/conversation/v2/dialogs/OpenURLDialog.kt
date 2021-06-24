package org.thoughtcrime.securesms.conversation.v2.dialogs

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import kotlinx.android.synthetic.main.dialog_open_url.view.*
import network.loki.messenger.R
import org.thoughtcrime.securesms.conversation.v2.utilities.BaseDialog

class OpenURLDialog(private val url: String) : BaseDialog() {

    override fun setContentView(builder: AlertDialog.Builder) {
        val contentView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_open_url, null)
        val explanation = resources.getString(R.string.dialog_open_url_explanation, url)
        val spannable = SpannableStringBuilder(explanation)
        val startIndex = explanation.indexOf(url)
        spannable.setSpan(StyleSpan(Typeface.BOLD), startIndex, startIndex + url.count(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        contentView.openURLExplanationTextView.text = spannable
        contentView.cancelButton.setOnClickListener { dismiss() }
        contentView.openURLButton.setOnClickListener { open() }
        builder.setView(contentView)
    }

    private fun open() {
        // TODO: Implement
    }
}