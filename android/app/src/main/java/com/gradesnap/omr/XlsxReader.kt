package com.gradesnap.omr

import android.content.ContentResolver
import android.net.Uri
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Lightweight XLSX reader — no Apache POI dependency.
 * Dùng ZipInputStream + XmlPullParser (built-in Android) để parse .xlsx format.
 * Trả về List<List<String>> — mỗi row là 1 list cell values (có thể có cell trống giữa).
 */
object XlsxReader {

    /**
     * Đọc sheet đầu tiên của file XLSX từ InputStream.
     * @return rows dưới dạng List<List<String>>, hoặc null nếu parse lỗi.
     */
    fun readFirstSheet(stream: InputStream): List<List<String>>? {
        return try {
            // XLSX là ZIP — đọc các entry cần thiết
            val zipBytes = stream.readBytes()

            // 1. Đọc shared strings (nếu có)
            val sharedStrings = readSharedStrings(zipBytes)

            // 2. Đọc worksheet sheet1
            readSheet(zipBytes, sharedStrings)
        } catch (e: Exception) {
            null
        }
    }

    fun readFromUri(resolver: ContentResolver, uri: Uri): List<List<String>>? {
        return try {
            resolver.openInputStream(uri)?.use { readFirstSheet(it) }
        } catch (e: Exception) {
            null
        }
    }

    // ─── Parse xl/sharedStrings.xml ──────────────────────────────────────────

    private fun readSharedStrings(zipBytes: ByteArray): List<String> {
        val strings = mutableListOf<String>()
        val zip = ZipInputStream(zipBytes.inputStream())
        var entry = zip.nextEntry
        while (entry != null) {
            if (entry.name == "xl/sharedStrings.xml") {
                val content = zip.readBytes()
                parseSharedStrings(content.inputStream(), strings)
                break
            }
            entry = zip.nextEntry
        }
        zip.close()
        return strings
    }

    private fun parseSharedStrings(stream: InputStream, out: MutableList<String>) {
        val factory = XmlPullParserFactory.newInstance()
        val xpp = factory.newPullParser()
        xpp.setInput(stream, "UTF-8")
        var inSi = false
        val sb = StringBuilder()
        var eventType = xpp.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> when (xpp.name) {
                    "si" -> { inSi = true; sb.clear() }
                    "t"  -> if (inSi) { /* text will be next TEXT event */ }
                }
                XmlPullParser.TEXT -> if (inSi) sb.append(xpp.text)
                XmlPullParser.END_TAG -> when (xpp.name) {
                    "si" -> { out.add(sb.toString()); inSi = false }
                }
            }
            eventType = xpp.next()
        }
    }

    // ─── Parse xl/worksheets/sheet1.xml ──────────────────────────────────────

    private fun readSheet(zipBytes: ByteArray, sharedStrings: List<String>): List<List<String>>? {
        val zip = ZipInputStream(zipBytes.inputStream())
        var entry = zip.nextEntry
        while (entry != null) {
            // Support sheet1 at various paths
            if (entry.name.matches(Regex("xl/worksheets/sheet1?\\.xml"))) {
                val content = zip.readBytes()
                zip.close()
                return parseSheet(content.inputStream(), sharedStrings)
            }
            entry = zip.nextEntry
        }
        zip.close()
        return null
    }

    private fun parseSheet(stream: InputStream, sharedStrings: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val factory = XmlPullParserFactory.newInstance()
        val xpp = factory.newPullParser()
        xpp.setInput(stream, "UTF-8")

        var currentRow = mutableListOf<String>()
        var inCell = false
        var inValue = false
        var cellType = ""          // "s" = shared string, "inlineStr" = inline, "" = number/date
        var cellCol = -1           // 0-based column index of current cell
        var lastColInRow = -1

        var eventType = xpp.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> when (xpp.name) {
                    "row" -> {
                        currentRow = mutableListOf()
                        lastColInRow = -1
                    }
                    "c" -> {
                        inCell = true
                        cellType = xpp.getAttributeValue(null, "t") ?: ""
                        // r attribute like "A3", "B3"
                        val ref = xpp.getAttributeValue(null, "r") ?: ""
                        cellCol = colIndexFromRef(ref)
                    }
                    "v", "t" -> if (inCell) inValue = true
                }
                XmlPullParser.TEXT -> if (inCell && inValue) {
                    val rawValue = xpp.text ?: ""
                    val cellValue = when (cellType) {
                        "s" -> sharedStrings.getOrElse(rawValue.trim().toIntOrNull() ?: -1) { rawValue }
                        "b" -> if (rawValue == "1") "TRUE" else "FALSE"
                        else -> rawValue
                    }
                    // Fill sparse columns with empty strings up to this column
                    while (currentRow.size < cellCol) currentRow.add("")
                    if (currentRow.size == cellCol) currentRow.add(cellValue)
                    else if (cellCol >= 0) currentRow[cellCol] = cellValue
                    lastColInRow = maxOf(lastColInRow, cellCol)
                }
                XmlPullParser.END_TAG -> when (xpp.name) {
                    "c" -> { inCell = false; inValue = false; cellType = "" }
                    "v", "t" -> inValue = false
                    "row" -> if (currentRow.isNotEmpty()) rows.add(currentRow.toList())
                }
            }
            eventType = xpp.next()
        }
        return rows
    }

    /** Convert cell reference like "A1", "AB12" → 0-based column index. */
    private fun colIndexFromRef(ref: String): Int {
        var col = 0
        for (ch in ref) {
            if (ch.isLetter()) col = col * 26 + (ch.uppercaseChar() - 'A' + 1)
            else break
        }
        return col - 1  // 0-based
    }
}
