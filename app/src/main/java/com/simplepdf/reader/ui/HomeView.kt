package com.simplepdf.reader.ui

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.simplepdf.reader.R
import com.simplepdf.reader.data.PdfDocument
import java.text.DateFormat
import java.util.Date

class HomeView(
    context: Context,
    onOpenPicker: () -> Unit,
    onRefresh: () -> Unit,
    onGrantAccess: () -> Unit,
    private val onOpen: (PdfDocument) -> Unit,
) : LinearLayout(context) {
    private val count = context.label("", 12f, true)
    private val status = context.label("", 14f)
    private val empty = context.label("", 16f).apply { gravity = Gravity.CENTER }
    private val permission = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(context.dp(20), context.dp(20), context.dp(20), context.dp(20))
        background = rounded(Palette.softGreen, context.dp(20).toFloat())
        addView(context.label(context.getString(R.string.storage_title), 19f, true))
        addView(context.label(context.getString(R.string.storage_message)).apply {
            setPadding(0, context.dp(10), 0, context.dp(14))
        })
        addView(context.action(context.getString(R.string.allow_access), onGrantAccess))
    }
    private val adapter = LibraryAdapter()
    private var documents: List<PdfDocument> = emptyList()
    private var query = ""
    private var busy = false
    private var hasAccess = false

    init {
        orientation = VERTICAL
        setPadding(context.dp(20), context.dp(12), context.dp(20), 0)
        setBackgroundColor(Palette.background)
        addView(context.label(context.getString(R.string.app_name), 13f, true).apply {
            setTextColor(Palette.green)
        })
        addView(context.label(context.getString(R.string.library), 32f, true).apply {
            setPadding(0, context.dp(8), 0, 0)
        })
        addView(context.label(context.getString(R.string.library_subtitle)).apply {
            setTextColor(Palette.muted)
            setPadding(0, context.dp(4), 0, context.dp(18))
        })
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(context.action(context.getString(R.string.open_pdf), onOpenPicker),
                LayoutParams(0, context.dp(48), 1f))
            addView(context.action(context.getString(R.string.refresh), onRefresh),
                LayoutParams(0, context.dp(48), 1f).apply { marginStart = context.dp(10) })
        })
        addView(EditText(context).apply {
            hint = context.getString(R.string.search_hint)
            textSize = 16f
            isSingleLine = true
            setTextColor(Palette.ink)
            setHintTextColor(Palette.muted)
            setPadding(context.dp(16), 0, context.dp(16), 0)
            background = rounded(android.graphics.Color.WHITE, context.dp(14).toFloat())
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    query = s?.toString().orEmpty()
                    filter()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(52)).apply {
            topMargin = context.dp(18)
            bottomMargin = context.dp(14)
        })
        addView(permission, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(context.label(context.getString(R.string.sort_label), 10f, true).apply {
                setTextColor(Palette.muted)
                letterSpacing = 0.08f
            }, LayoutParams(0, context.dp(40), 1f))
            addView(count)
        })
        addView(status)
        addView(empty, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(12)
        })
        addView(RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@HomeView.adapter
            clipToPadding = false
            setPadding(0, 0, 0, context.dp(100))
        }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun setAccess(granted: Boolean) {
        hasAccess = granted
        permission.visibility = if (granted) GONE else VISIBLE
        filter()
    }

    fun showLoading() {
        busy = true
        status.text = context.getString(R.string.scanning)
        status.visibility = VISIBLE
        filter()
    }

    fun showDocuments(items: List<PdfDocument>, warning: String? = null) {
        busy = false
        documents = items
        status.text = warning.orEmpty()
        status.visibility = if (warning == null) GONE else VISIBLE
        filter()
    }

    private fun filter() {
        val filtered = documents.filter { it.title.contains(query.trim(), ignoreCase = true) }
        adapter.items = filtered
        adapter.notifyDataSetChanged()
        count.text = context.getString(R.string.pdf_count, filtered.size)
        empty.text = when {
            query.isNotBlank() -> context.getString(R.string.no_matches)
            else -> context.getString(R.string.empty_library) + "\n\n" +
                context.getString(R.string.empty_library_detail)
        }
        empty.visibility = if (filtered.isEmpty() && !busy && hasAccess) VISIBLE else GONE
    }

    private inner class LibraryAdapter : RecyclerView.Adapter<LibraryHolder>() {
        var items: List<PdfDocument> = emptyList()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LibraryHolder {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dp(14), context.dp(14), context.dp(14), context.dp(14))
                background = rounded(android.graphics.Color.WHITE, context.dp(16).toFloat())
                layoutParams = RecyclerView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = context.dp(9)
                }
                isFocusable = true
            }
            row.addView(context.label("PDF", 11f, true).apply {
                gravity = Gravity.CENTER
                setTextColor(Palette.green)
                background = rounded(Palette.softGreen, context.dp(10).toFloat())
            }, LayoutParams(context.dp(44), context.dp(54)))
            val body = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(context.dp(14), 0, 0, 0)
            }
            val title = context.label("", 16f, true).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val details = context.label("", 11f).apply {
                setTextColor(Palette.muted)
                setPadding(0, context.dp(6), 0, 0)
                maxLines = 2
            }
            body.addView(title)
            body.addView(details)
            row.addView(body, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            return LibraryHolder(row, title, details)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: LibraryHolder, position: Int) {
            val document = items[position]
            holder.title.text = document.title
            val modified = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(document.modifiedAt))
            holder.details.text = context.getString(R.string.file_details, modified,
                Formatter.formatShortFileSize(context, document.sizeBytes))
            holder.itemView.setOnClickListener { onOpen(document) }
        }
    }

    private class LibraryHolder(
        view: View,
        val title: android.widget.TextView,
        val details: android.widget.TextView,
    ) : RecyclerView.ViewHolder(view)
}
