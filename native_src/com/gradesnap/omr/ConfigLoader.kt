package com.gradesnap.omr

import android.content.Context
import org.json.JSONObject

// ─── Data models ──────────────────────────────────────────────────────────────

data class OmrConfig(
    val imageWidth:  Int,
    val imageHeight: Int,
    val mssv:        RegionConfig?,
    val key:         RegionConfig?,
    val questions:   QuestionConfig
)

data class RegionConfig(
    val x1: Int, val y1: Int, val x2: Int, val y2: Int,
    val rows: Int, val cols: Int,
    val type: String = "numeric",
    val digits: Int = 0
)

data class QuestionBlock(
    val label:         String,
    val questionStart: Int,
    val questionEnd:   Int,
    val x1: Int, val y1: Int, val x2: Int, val y2: Int,
    val rows: Int, val cols: Int
)

data class QuestionConfig(
    val options:        List<String>,
    val totalQuestions: Int,
    val blocks:         List<QuestionBlock>
)

// ─── Loader ───────────────────────────────────────────────────────────────────

object ConfigLoader {

    /**
     * Load omr_layout_config.json từ assets
     */
    fun load(context: Context, fileName: String = "omr_layout_config.json"): OmrConfig {
        val json = context.assets.open(fileName).bufferedReader().readText()
        val root = JSONObject(json)

        val imgSize = root.getJSONObject("image_size")
        val regions = root.getJSONObject("regions")

        // MSSV
        val mssvRegion = regions.optJSONObject("mssv")?.let { parseRegion(it) }

        // Key code
        val keyRegion = regions.optJSONObject("key")?.let { parseRegion(it) }

        // Questions
        val qObj = regions.getJSONObject("questions")
        val optionsArr = qObj.getJSONArray("options")
        val options = (0 until optionsArr.length()).map { optionsArr.getString(it) }
        val totalQ   = qObj.getInt("total_questions")

        val blocksArr = qObj.getJSONArray("blocks")
        val blocks    = (0 until blocksArr.length()).map { i ->
            val b    = blocksArr.getJSONObject(i)
            val box  = b.getJSONObject("box")
            val grid = b.getJSONObject("grid")
            QuestionBlock(
                label         = b.optString("label", "Block$i"),
                questionStart = b.getInt("question_start"),
                questionEnd   = b.getInt("question_end"),
                x1 = box.getInt("x1"), y1 = box.getInt("y1"),
                x2 = box.getInt("x2"), y2 = box.getInt("y2"),
                rows = grid.getInt("rows"), cols = grid.getInt("cols")
            )
        }

        return OmrConfig(
            imageWidth  = imgSize.getInt("width"),
            imageHeight = imgSize.getInt("height"),
            mssv        = mssvRegion,
            key         = keyRegion,
            questions   = QuestionConfig(options, totalQ, blocks)
        )
    }

    private fun parseRegion(obj: JSONObject): RegionConfig {
        val box  = obj.getJSONObject("box")
        val grid = obj.getJSONObject("grid")
        return RegionConfig(
            x1 = box.getInt("x1"), y1 = box.getInt("y1"),
            x2 = box.getInt("x2"), y2 = box.getInt("y2"),
            rows   = grid.getInt("rows"),
            cols   = grid.getInt("cols"),
            type   = obj.optString("type", "numeric"),
            digits = obj.optInt("digits", 0)
        )
    }
}
