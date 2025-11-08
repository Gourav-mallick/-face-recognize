package com.example.login.view

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.recyclerview.widget.RecyclerView
import com.example.login.databinding.ItemCourseCheckboxBinding
import com.example.login.db.entity.Course

class SubjectSelectAdapter(
    private val courseList: List<Course>,
    private val onSelectionChanged: (List<String>) -> Unit
) : RecyclerView.Adapter<SubjectSelectAdapter.CourseViewHolder>() {

    private val selectedCourses = mutableSetOf<String>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CourseViewHolder {
        val binding = ItemCourseCheckboxBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return CourseViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CourseViewHolder, position: Int) {
        val course = courseList[position]
        holder.binding.checkboxCourse.text = "${course.courseTitle} (${course.courseShortName})"
        holder.binding.checkboxCourse.setOnCheckedChangeListener(null)
        holder.binding.checkboxCourse.isChecked = selectedCourses.contains(course.courseId)

        holder.binding.checkboxCourse.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            if (isChecked) selectedCourses.add(course.courseId)
            else selectedCourses.remove(course.courseId)
            onSelectionChanged(selectedCourses.toList())
        }
    }

    override fun getItemCount() = courseList.size

    inner class CourseViewHolder(val binding: ItemCourseCheckboxBinding) :
        RecyclerView.ViewHolder(binding.root)
}
