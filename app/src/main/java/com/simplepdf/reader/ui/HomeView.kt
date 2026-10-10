package com.simplepdf.reader.ui

import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import android.text.Editable
import android.text.TextWatcher
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
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
) : FrameLayout(context) {
    private val count = context.label("", 12f, true)
    private val status = context.label("", 14f)
    private val empty = context.label("", 16f).apply { gravity = Gravity.CENTER }
    private val permission = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(20), context.dp(20), context.dp(20), context.dp(20))
        background = rounded(Palette.softGreen, context.dp(20).toFloat())
        addView(context.label(context.getString(R.string.storage_title), 19f, true))
        addView(context.label(context.getString(R.string.storage_message)).apply {
            setPadding(0, context.dp(10), 0, context.dp(14))
        })
        addView(context.action(context.getString(R.string.allow_access), onGrantAccess))
    }
    private val adapter = LibraryAdapter()
    private val header = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = RecyclerView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }
    private val search = EditText(context)
    private val list = RecyclerView(context)
    private var documents: List<PdfDocument> = emptyList()
    private var query = ""
    private var busy = false
    private var hasAccess = false
    private var pendingScrollState: Parcelable? = null

    init {
        setBackgroundColor(Palette.background)
        header.addView(context.label(context.getString(R.string.app_name), 13f, true).apply {
            setTextColor(Palette.green)
        })
        header.addView(context.label(context.getString(R.string.library), 32f, true).apply {
            setPadding(0, context.dp(8), 0, 0)
        })
        header.addView(context.label(context.getString(R.string.library_subtitle)).apply {
            setTextColor(Palette.muted)
            setPadding(0, context.dp(4), 0, context.dp(18))
        })
        header.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(context.action(context.getString(R.string.open_pdf), onOpenPicker),
                LinearLayout.LayoutParams(0, context.dp(48), 1f))
            addView(context.action(context.getString(R.string.refresh), onRefresh),
                LinearLayout.LayoutParams(0, context.dp(48), 1f).apply { marginStart = context.dp(10) })
        })
        header.addView(search.apply {
            id = R.id.library_search
            isSaveEnabled = false
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
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, context.dp(52)).apply {
            topMargin = context.dp(18)
            bottomMargin = context.dp(14)
        })
        header.addView(permission, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        header.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(context.label(context.getString(R.string.sort_label), 10f, true).apply {
                setTextColor(Palette.muted)
                letterSpacing = 0.08f
            }, LinearLayout.LayoutParams(0, context.dp(40), 1f))
            addView(count)
        })
        header.addView(status)
        header.addView(empty, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(12)
        })
        addView(list.apply {
            id = R.id.library_list
            isSaveEnabled = false
            layoutManager = LinearLayoutManager(context)
            adapter = this@HomeView.adapter
            itemAnimator = null
            clipToPadding = false
            setPadding(context.dp(20), context.dp(12), context.dp(20), context.dp(100))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun showLibrary(items: List<PdfDocument>, access: Boolean, loading: Boolean, warning: String?) {
        documents = items
        hasAccess = access
        busy = loading
        permission.visibility = if (access) GONE else VISIBLE
        status.text = if (loading) context.getString(R.string.scanning) else warning.orEmpty()
        status.visibility = if (loading || warning != null) VISIBLE else GONE
        filter()
        if (!loading || items.isNotEmpty()) {
            pendingScrollState?.let { list.layoutManager?.onRestoreInstanceState(it) }
            pendingScrollState = null
        }
    }

    fun saveState(state: Bundle) {
        state.putString("library.query", query)
        state.putParcelable("library.scroll", pendingScrollState ?: list.layoutManager?.onSaveInstanceState())
    }

    @Suppress("DEPRECATION")
    fun restoreState(state: Bundle?) {
        search.setText(state?.getString("library.query").orEmpty())
        pendingScrollState = state?.getParcelable("library.scroll")
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

    private inner class LibraryAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<PdfDocument> = emptyList()

        override fun getItemViewType(position: Int): Int = if (position == 0) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 0) return object : RecyclerView.ViewHolder(header) {}
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
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
            }, LinearLayout.LayoutParams(context.dp(44), context.dp(54)))
            val body = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
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
            row.addView(body, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            return LibraryHolder(row, title, details)
        }

        override fun getItemCount(): Int = items.size + 1

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder !is LibraryHolder) return
            val document = items[position - 1]
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
