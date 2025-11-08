package com.example.login.view


import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.recyclerview.widget.RecyclerView
import com.example.login.databinding.ItemClassCheckboxBinding
import com.example.login.db.entity.Class

class ClassSelectAdapter(
    private val classList: List<Class>,
    private val preSelectedIds: List<String>,
    private val onClassCheckedChange: (classId: String, isChecked: Boolean, wasPreSelected: Boolean) -> Unit
) : RecyclerView.Adapter<ClassSelectAdapter.ClassViewHolder>() {

    private val selectedClasses = mutableSetOf<String>()

    init {
        selectedClasses.addAll(preSelectedIds)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ClassViewHolder {
        val binding = ItemClassCheckboxBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ClassViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ClassViewHolder, position: Int) {
        val item = classList[position]
        val wasPreSelected = preSelectedIds.contains(item.classId)

        holder.binding.checkboxClass.text = item.classShortName
        holder.binding.checkboxClass.setOnCheckedChangeListener(null)
        holder.binding.checkboxClass.isChecked = selectedClasses.contains(item.classId)

        holder.binding.checkboxClass.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            if (isChecked) selectedClasses.add(item.classId)
            else selectedClasses.remove(item.classId)
            onClassCheckedChange(item.classId, isChecked, wasPreSelected)
        }
    }

    override fun getItemCount() = classList.size

    fun getSelectedClasses(): List<String> = selectedClasses.toList()

    inner class ClassViewHolder(val binding: ItemClassCheckboxBinding) :
        RecyclerView.ViewHolder(binding.root)
}

