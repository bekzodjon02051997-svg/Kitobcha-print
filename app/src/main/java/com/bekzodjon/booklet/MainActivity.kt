package com.bekzodjon.booklet

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.OpenableColumns
import android.text.InputType
import android.view.View
import android.widget.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class MainActivity : Activity() {

    private val REQ_OPEN = 1
    private val REQ_SAVE = 2

    private var pdfUri: Uri? = null
    private var pdfName = "kitob"
    private var pageCount = 0

    private lateinit var info: TextView
    private lateinit var status: TextView
    private lateinit var bar: ProgressBar
    private lateinit var rbLeft: RadioButton
    private lateinit var rbBoth: RadioButton
    private lateinit var rbFront: RadioButton
    private lateinit var rbLetter: RadioButton
    private lateinit var marginEt: EditText
    private lateinit var cbReverse: CheckBox
    private lateinit var btnSave: Button
    private lateinit var btnPrint: Button
    private lateinit var btnOpen: Button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(t: String) = TextView(this).apply {
        text = t; textSize = 18f; setTextColor(Color.BLACK)
        setPadding(0, dp(16), 0, dp(4)); setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun radio(t: String, checked: Boolean = false) = RadioButton(this).apply {
        text = t; textSize = 17f; isChecked = checked; id = View.generateViewId()
    }

    private fun group(vararg rbs: RadioButton) = RadioGroup(this).apply {
        orientation = RadioGroup.VERTICAL
        rbs.forEach { addView(it) }
    }

    private fun button(t: String) = Button(this).apply { text = t; textSize = 17f; isAllCaps = false }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "Kitobcha PDF"; textSize = 26f; setTextColor(Color.BLACK)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        btnOpen = button("1. PDF faylni tanlash")
        root.addView(btnOpen)
        info = TextView(this).apply { text = "Fayl tanlanmagan"; textSize = 16f; setPadding(0, dp(8), 0, 0) }
        root.addView(info)

        root.addView(label("Bog'lash (Binding)"))
        rbLeft = radio("Chap", true)
        root.addView(group(rbLeft, radio("O'ng")))

        root.addView(label("Qaysi tomon (Booklet subset)"))
        rbBoth = radio("Ikkala tomon", true)
        rbFront = radio("Faqat old tomon (Front)")
        root.addView(group(rbBoth, rbFront, radio("Faqat orqa tomon (Back)")))
        // radio tugmalar tartibi: BOTH, FRONT, BACK
        val subsetGroup = rbBoth.parent as RadioGroup
        subsetGroup.tag = "subset"

        root.addView(label("Qog'oz o'lchami"))
        rbLetter = radio("Letter (11 x 8.5 dyuym)", true)
        root.addView(group(rbLetter, radio("A4 (11.69 x 8.27 dyuym)")))

        root.addView(label("Chekka (Margins), dyuym"))
        marginEt = EditText(this).apply {
            setText("0.2"); textSize = 18f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        root.addView(marginEt)

        cbReverse = CheckBox(this).apply { text = "Sahifalarni teskari tartibda (Reverse pages)"; textSize = 17f }
        root.addView(cbReverse)

        root.addView(TextView(this).apply {
            text = "Avto-aylantirish va avto-markazlash doim yoqilgan."
            textSize = 14f; setPadding(0, dp(4), 0, dp(8))
        })

        btnSave = button("2. Booklet PDF ni saqlash")
        btnPrint = button("3. Chop etish")
        root.addView(btnSave)
        root.addView(btnPrint)

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { visibility = View.GONE }
        root.addView(bar)
        status = TextView(this).apply { textSize = 16f; setPadding(0, dp(8), 0, 0) }
        root.addView(status)

        btnOpen.setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "application/pdf"
            }
            startActivityForResult(i, REQ_OPEN)
        }
        btnSave.setOnClickListener {
            if (!ready()) return@setOnClickListener
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "application/pdf"
                putExtra(Intent.EXTRA_TITLE, "${pdfName}_booklet.pdf")
            }
            startActivityForResult(i, REQ_SAVE)
        }
        btnPrint.setOnClickListener {
            if (!ready()) return@setOnClickListener
            val f = File(cacheDir, "booklet_print.pdf")
            generate(f) { pages -> printFile(f, pages) }
        }
    }

    private fun ready(): Boolean {
        if (pdfUri == null) { toast("Avval PDF faylni tanlang"); return false }
        return true
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun options(): Options {
        val margin = (marginEt.text.toString().replace(',', '.').toFloatOrNull() ?: 0.2f).coerceIn(0f, 1.5f)
        val subsetGroup = rbBoth.parent as RadioGroup
        val subset = when (subsetGroup.indexOfChild(subsetGroup.findViewById(subsetGroup.checkedRadioButtonId))) {
            1 -> Subset.FRONT
            2 -> Subset.BACK
            else -> Subset.BOTH
        }
        val bindGroup = rbLeft.parent as RadioGroup
        val binding = if (bindGroup.indexOfChild(bindGroup.findViewById(bindGroup.checkedRadioButtonId)) == 1)
            Binding.RIGHT else Binding.LEFT
        val letter = rbLetter.isChecked
        return Options(
            binding, subset, margin, cbReverse.isChecked,
            if (letter) 792f else 842f,
            if (letter) 612f else 595f
        )
    }

    private fun setBusy(b: Boolean) {
        btnSave.isEnabled = !b; btnPrint.isEnabled = !b; btnOpen.isEnabled = !b
        bar.visibility = if (b) View.VISIBLE else View.GONE
        if (b) { bar.isIndeterminate = true; status.text = "Tayyorlanmoqda..." }
    }

    /** Booklet PDF ni `out` fayliga yaratadi (orqa oqimda), so'ng `done(chiqishSahifalari)` ni chaqiradi. */
    private fun generate(out: File, done: (Int) -> Unit) {
        val uri = pdfUri ?: return
        val opts = options()
        setBusy(true)
        Thread {
            try {
                val n = BookletBuilder.build(this, uri, opts, out) { c, t ->
                    runOnUiThread {
                        bar.isIndeterminate = false; bar.max = t; bar.progress = c
                        status.text = "Sahifa $c / $t"
                    }
                }
                runOnUiThread { setBusy(false); status.text = "Tayyor: $n ta sahifa"; done(n) }
            } catch (e: Throwable) {
                runOnUiThread {
                    setBusy(false)
                    status.text = "Xatolik: ${e.message}"
                    toast("Xatolik: ${e.message}")
                }
            }
        }.start()
    }

    private fun printFile(f: File, pages: Int) {
        val o = options()
        val media = if (o.paperW == 792f) PrintAttributes.MediaSize.NA_LETTER else PrintAttributes.MediaSize.ISO_A4
        val attrs = PrintAttributes.Builder()
            .setMediaSize(media.asLandscape())
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()
        val pm = getSystemService(PRINT_SERVICE) as PrintManager
        pm.print("${pdfName}_booklet", FileAdapter(f, pages, "${pdfName}_booklet.pdf"), attrs)
    }

    class FileAdapter(private val file: File, private val pages: Int, private val name: String) :
        PrintDocumentAdapter() {
        override fun onLayout(
            old: PrintAttributes?, new: PrintAttributes?, cancel: CancellationSignal?,
            cb: LayoutResultCallback, extras: Bundle?
        ) {
            if (cancel?.isCanceled == true) { cb.onLayoutCancelled(); return }
            val info = PrintDocumentInfo.Builder(name)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(pages).build()
            cb.onLayoutFinished(info, true)
        }

        override fun onWrite(
            ranges: Array<out PageRange>?, dest: ParcelFileDescriptor,
            cancel: CancellationSignal?, cb: WriteResultCallback
        ) {
            try {
                FileInputStream(file).use { i ->
                    FileOutputStream(dest.fileDescriptor).use { o -> i.copyTo(o) }
                }
                cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                cb.onWriteFailed(e.message)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_OPEN -> {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) { }
                try {
                    contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
                        PdfRenderer(pfd).use { pageCount = it.pageCount }
                    }
                } catch (e: Exception) {
                    toast("PDF ochilmadi: ${e.message}"); return
                }
                pdfUri = uri
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) pdfName = c.getString(idx).removeSuffix(".pdf").removeSuffix(".PDF")
                    }
                }
                val sheets = BookletBuilder.sheetsFor(pageCount)
                val blanks = sheets * 4 - pageCount
                info.text = "$pdfName\nSahifalar: $pageCount  |  Varaqlar: $sheets" +
                    (if (blanks > 0) "  |  Oxiriga $blanks ta bo'sh sahifa qo'shiladi" else "")
            }
            REQ_SAVE -> {
                val f = File(cacheDir, "booklet_save.pdf")
                generate(f) {
                    try {
                        contentResolver.openOutputStream(uri)!!.use { o ->
                            FileInputStream(f).use { it.copyTo(o) }
                        }
                        toast("PDF saqlandi")
                    } catch (e: Exception) {
                        toast("Saqlashda xatolik: ${e.message}")
                    }
                }
            }
        }
    }
}
