package com.example.login.view

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.example.login.databinding.ItemClassOverviewBinding

class AttendanceOverviewAdapter(
    private val classList: List<ClassOverviewData>,
    private val onEditClick: (String) -> Unit
) : RecyclerView.Adapter<AttendanceOverviewAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemClassOverviewBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemClassOverviewBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun getItemCount(): Int = classList.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val data = classList[position]
        holder.binding.textClassName.text = "Class: ${data.className}"
        holder.binding.textTotal.text = "Total Students: ${data.totalStudents}"
        holder.binding.textPresent.text = "Present: ${data.presentCount}"
      //  holder.binding.textPresentStudents.text = "Present Students:\n${data.presentStudents.joinToString("\n")}"

    }
}
