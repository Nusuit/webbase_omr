package com.gradesnap.omr

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class AnswerAdapter(private val items: List<AnswerItem>) :
    RecyclerView.Adapter<AnswerAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvQuestion: TextView = view.findViewById(R.id.tvQuestionNumber)
        val tvAnswer: TextView = view.findViewById(R.id.tvAnswer)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_answer, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvQuestion.text = "Câu ${item.questionNumber}"
        holder.tvAnswer.text = item.answer ?: "—"

        val colorRes = if (item.answer != null) {
            android.R.color.holo_green_dark
        } else {
            android.R.color.holo_red_light
        }
        holder.tvAnswer.setTextColor(
            ContextCompat.getColor(holder.itemView.context, colorRes)
        )
    }

    override fun getItemCount() = items.size
}
