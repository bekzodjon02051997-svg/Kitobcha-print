package com.bekzodjon.booklet

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.util.Matrix
import java.io.File

enum class Binding { LEFT, RIGHT }
enum class Subset { BOTH, FRONT, BACK }

data class Options(
    val binding: Binding,
    val subset: Subset,
    val marginInch: Float,
    val reverse: Boolean,
    val paperW: Float,   // landscape, points (1 inch = 72 pt)
    val paperH: Float
)

object BookletBuilder {

    fun sheetsFor(pageCount: Int) = (pageCount + 3) / 4

    /** Har bir chiqish sahifasi uchun (chap, o'ng) manba sahifa indekslari; -1 = bo'sh. */
    fun layout(pageCount: Int, o: Options): List<Pair<Int, Int>> {
        val sheets = sheetsFor(pageCount)
        val n = sheets * 4
        val out = mutableListOf<Pair<Int, Int>>()
        for (i in 0 until sheets) {
            var front = Pair(n - 1 - 2 * i, 2 * i)
            var back = Pair(2 * i + 1, n - 2 - 2 * i)
            if (o.binding == Binding.RIGHT) {
                front = Pair(front.second, front.first)
                back = Pair(back.second, back.first)
            }
            if (o.subset != Subset.BACK) out.add(front)
            if (o.subset != Subset.FRONT) out.add(back)
        }
        if (o.reverse) out.reverse()
        return out.map { (l, r) ->
            Pair(if (l >= pageCount) -1 else l, if (r >= pageCount) -1 else r)
        }
    }

    /** Booklet PDF yaratadi va `out` fayliga yozadi. Chiqish sahifalari sonini qaytaradi. */
    fun build(ctx: Context, uri: Uri, o: Options, out: File, progress: (Int, Int) -> Unit): Int {
        PDFBoxResourceLoader.init(ctx.applicationContext)
        val src = ctx.contentResolver.openInputStream(uri)!!.use { PDDocument.load(it) }
        val dst = PDDocument()
        try {
            val lu = LayerUtility(dst)
            val list = layout(src.numberOfPages, o)
            val m = o.marginInch * 72f
            val cellW = (o.paperW - 2 * m) / 2f
            val cellH = o.paperH - 2 * m

            fun place(cs: PDPageContentStream, idx: Int, cellX: Float) {
                if (idx < 0) return
                val form = lu.importPageAsForm(src, idx)
                val bb = form.bBox
                val bw = bb.width
                val bh = bb.height
                if (bw <= 0f || bh <= 0f) return
                val s = minOf(cellW / bw, cellH / bh)
                val tx = cellX + (cellW - bw * s) / 2f - bb.lowerLeftX * s
                val ty = m + (cellH - bh * s) / 2f - bb.lowerLeftY * s
                cs.saveGraphicsState()
                cs.transform(Matrix(s, 0f, 0f, s, tx, ty))
                cs.drawForm(form)
                cs.restoreGraphicsState()
            }

            list.forEachIndexed { k, (l, r) ->
                val page = PDPage(PDRectangle(o.paperW, o.paperH))
                dst.addPage(page)
                PDPageContentStream(dst, page).use { cs ->
                    place(cs, l, m)
                    place(cs, r, m + cellW)
                }
                progress(k + 1, list.size)
            }
            dst.save(out)
            return list.size
        } finally {
            dst.close()
            src.close()
        }
    }
}
