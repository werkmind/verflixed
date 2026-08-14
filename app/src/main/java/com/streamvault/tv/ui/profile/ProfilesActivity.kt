package com.streamvault.tv.ui.profile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.streamvault.tv.ui.util.ScaledAppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.streamvault.tv.R
import com.streamvault.tv.VerflixedApp
import com.streamvault.tv.data.db.ProfileEntity
import com.streamvault.tv.databinding.ActivityProfilesBinding
import com.streamvault.tv.ui.util.FocusFx
import com.streamvault.tv.ui.util.TvLinearLayoutManager
import com.streamvault.tv.ui.util.UiSound
import com.streamvault.tv.util.toVfMessage
import kotlinx.coroutines.launch

class ProfilesActivity : ScaledAppCompatActivity() {
    private lateinit var binding: ActivityProfilesBinding
    private val prefs by lazy { (application as VerflixedApp).container.prefs }
    private val adapter = ProfileAdapter(
        onSelect = { switchTo(it) },
        onEdit = { edit(it.id) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfilesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.profileList.layoutManager = TvLinearLayoutManager(this)
        binding.profileList.adapter = adapter

        FocusFx.bindScale(binding.btnAddProfile, 1.04f, prefs)
        FocusFx.bindScale(binding.btnCloseProfiles, 1.04f, prefs)

        binding.btnAddProfile.setOnClickListener {
            UiSound.click(this, prefs)
            startActivity(
                Intent(this, ProfileEditActivity::class.java)
                    .putExtra(ProfileEditActivity.EXTRA_CREATE, true)
            )
        }
        binding.btnCloseProfiles.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val app = application as VerflixedApp
        lifecycleScope.launch {
            runCatching {
                val list = app.container.profiles.all()
                val active = app.container.profiles.active().id
                list to active
            }.onSuccess { (list, active) ->
                adapter.submit(list, active)
            }.onFailure {
                Toast.makeText(this@ProfilesActivity, it.toVfMessage(), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun switchTo(profile: ProfileEntity) {
        val app = application as VerflixedApp
        UiSound.success(this, prefs)
        lifecycleScope.launch {
            runCatching { app.container.profiles.switchTo(profile.id) }
                .onSuccess {
                    Toast.makeText(
                        this@ProfilesActivity,
                        getString(R.string.profile_switched, it.name),
                        Toast.LENGTH_SHORT
                    ).show()
                    setResult(RESULT_OK)
                    finish()
                }
                .onFailure {
                    Toast.makeText(this@ProfilesActivity, it.toVfMessage(), Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun edit(profileId: String) {
        UiSound.click(this, prefs)
        startActivity(
            Intent(this, ProfileEditActivity::class.java)
                .putExtra(ProfileEditActivity.EXTRA_PROFILE_ID, profileId)
        )
    }
}

private class ProfileAdapter(
    private val onSelect: (ProfileEntity) -> Unit,
    private val onEdit: (ProfileEntity) -> Unit
) : RecyclerView.Adapter<ProfileAdapter.VH>() {
    private val items = mutableListOf<ProfileEntity>()
    private var activeId: String? = null

    fun submit(data: List<ProfileEntity>, active: String) {
        items.clear()
        items.addAll(data)
        activeId = active
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_profile, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = items[position]
        holder.name.text = p.name
        holder.meta.text = if (p.id == activeId) "Aktiv" else "Tippen zum Wechseln"
        holder.select.text = if (p.id == activeId) "Aktiv" else holder.itemView.context.getString(R.string.profile_switch)
        Glide.with(holder.avatar)
            .load(p.avatarUrl)
            .placeholder(R.drawable.ic_verflixed_mark)
            .transform(CircleCrop())
            .into(holder.avatar)
        holder.select.setOnClickListener { onSelect(p) }
        holder.edit.setOnClickListener { onEdit(p) }
        holder.selectArea.setOnClickListener { onSelect(p) }
        FocusFx.bindScale(holder.selectArea, 1.03f)
        FocusFx.bindScale(holder.edit, 1.05f)
        FocusFx.bindScale(holder.select, 1.05f)
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val avatar: ImageView = itemView.findViewById(R.id.avatar)
        val name: TextView = itemView.findViewById(R.id.profileName)
        val meta: TextView = itemView.findViewById(R.id.profileMeta)
        val selectArea: View = itemView.findViewById(R.id.profileSelectArea)
        val edit: Button = itemView.findViewById(R.id.btnEdit)
        val select: Button = itemView.findViewById(R.id.btnSelect)
    }
}
