package com.streamvault.tv.ui.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.streamvault.tv.R
import com.streamvault.tv.VerflixedApp
import com.streamvault.tv.data.profile.AvatarCatalog
import com.streamvault.tv.data.profile.AvatarOption
import com.streamvault.tv.databinding.ActivityProfileEditBinding
import com.streamvault.tv.ui.util.FocusFx
import com.streamvault.tv.ui.util.UiSound
import com.streamvault.tv.util.toVfMessage
import kotlinx.coroutines.launch

class ProfileEditActivity : AppCompatActivity() {
    private lateinit var binding: ActivityProfileEditBinding
    private val prefs by lazy { (application as VerflixedApp).container.prefs }
    private var editingId: String? = null
    private var selectedAvatarUrl: String? = null
    private lateinit var avatarAdapter: AvatarAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        editingId = intent.getStringExtra(EXTRA_PROFILE_ID)
        val create = intent.getBooleanExtra(EXTRA_CREATE, editingId == null)

        binding.editorTitle.text = if (create) {
            getString(R.string.profile_create_title)
        } else getString(R.string.profile_edit_title)

        avatarAdapter = AvatarAdapter { opt ->
            UiSound.click(this@ProfileEditActivity, prefs)
            selectedAvatarUrl = opt.url
            avatarAdapter.select(opt.url)
        }
        binding.avatarGrid.layoutManager = GridLayoutManager(this, 7)
        binding.avatarGrid.adapter = avatarAdapter
        binding.avatarGrid.itemAnimator = null
        binding.avatarGrid.setHasFixedSize(true)

        FocusFx.bindScale(binding.btnSaveProfile, 1.04f, prefs)
        FocusFx.bindScale(binding.btnCancelEdit, 1.04f, prefs)
        FocusFx.bindScale(binding.btnDeleteProfile, 1.04f, prefs)

        binding.btnCancelEdit.setOnClickListener { finish() }
        binding.btnSaveProfile.setOnClickListener { save(create) }
        binding.btnDeleteProfile.setOnClickListener { deleteProfile() }
        binding.btnDeleteProfile.visibility = if (create) View.GONE else View.VISIBLE

        loadAvatarsAndProfile(create)
    }

    private fun loadAvatarsAndProfile(create: Boolean) {
        val app = application as VerflixedApp
        lifecycleScope.launch {
            val dice = AvatarCatalog.presetAvatars(
                binding.inputName.text?.toString().orEmpty().ifBlank { "Verflixed" }
            )
            val favs = runCatching { app.container.catalog.favoriteAvatarOptions() }
                .getOrDefault(emptyList())
            // Show local options instantly, then fold in the online people DB.
            avatarAdapter.submit((favs + dice).distinctBy { it.url })

            if (!create && editingId != null) {
                runCatching { app.container.profiles.all().first { it.id == editingId } }
                    .onSuccess { p ->
                        binding.inputName.setText(p.name)
                        selectedAvatarUrl = p.avatarUrl
                        avatarAdapter.select(p.avatarUrl)
                    }
            } else {
                selectedAvatarUrl = (favs + dice).firstOrNull()?.url
                avatarAdapter.select(selectedAvatarUrl)
            }

            val people = runCatching { app.container.catalog.personAvatarOptions() }
                .getOrDefault(emptyList())
            if (people.isNotEmpty()) {
                avatarAdapter.submit((favs + people + dice).distinctBy { it.url })
                avatarAdapter.select(selectedAvatarUrl)
            }
        }
    }

    private fun save(create: Boolean) {
        val app = application as VerflixedApp
        val name = binding.inputName.text?.toString().orEmpty().trim()
        if (name.isBlank()) {
            Toast.makeText(this, R.string.profile_name_required, Toast.LENGTH_SHORT).show()
            return
        }
        UiSound.success(this, prefs)
        lifecycleScope.launch {
            runCatching {
                if (create) {
                    val created = app.container.profiles.create(name, selectedAvatarUrl)
                    app.container.profiles.switchTo(created.id)
                    created
                } else {
                    app.container.profiles.update(editingId!!, name, selectedAvatarUrl)
                }
            }.onSuccess {
                Toast.makeText(this@ProfileEditActivity, R.string.profile_saved, Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            }.onFailure {
                Toast.makeText(this@ProfileEditActivity, it.toVfMessage(), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun deleteProfile() {
        val id = editingId ?: return
        val app = application as VerflixedApp
        lifecycleScope.launch {
            runCatching { app.container.profiles.delete(id) }
                .onSuccess {
                    Toast.makeText(this@ProfileEditActivity, R.string.profile_deleted, Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                }
                .onFailure {
                    Toast.makeText(this@ProfileEditActivity, it.toVfMessage(), Toast.LENGTH_LONG).show()
                }
        }
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_CREATE = "create"
    }
}

private class AvatarAdapter(
    private val onClick: (AvatarOption) -> Unit
) : RecyclerView.Adapter<AvatarAdapter.VH>() {
    private val items = mutableListOf<AvatarOption>()
    private var selectedUrl: String? = null

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long =
        items.getOrNull(position)?.url?.hashCode()?.toLong() ?: position.toLong()

    fun submit(data: List<AvatarOption>) {
        items.clear()
        items.addAll(data)
        notifyDataSetChanged()
    }

    fun select(url: String?) {
        val old = selectedUrl
        selectedUrl = url
        val oldIdx = items.indexOfFirst { it.url == old }
        val newIdx = items.indexOfFirst { it.url == url }
        if (oldIdx >= 0) notifyItemChanged(oldIdx)
        if (newIdx >= 0) notifyItemChanged(newIdx)
        if (oldIdx < 0 && newIdx < 0) notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_avatar, parent, false)
        FocusFx.bindScale(v, 1.08f)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val opt = items[position]
        val selected = opt.url == selectedUrl
        holder.itemView.alpha = if (selected) 1f else 0.72f
        holder.itemView.isSelected = selected
        holder.itemView.contentDescription = opt.label
        Glide.with(holder.image)
            .load(opt.url)
            .placeholder(R.drawable.ic_verflixed_mark)
            .transform(CircleCrop())
            .into(holder.image)
        holder.itemView.setOnClickListener { onClick(opt) }
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val image: ImageView = itemView.findViewById(R.id.avatarImage)
    }
}
