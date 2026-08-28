package com.replymint.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/**
 * Five static pages for the onboarding ViewPager2. Pages are plain layouts; anything dynamic
 * (sign-in state, permission checks) is wired by the activity through [onBind].
 */
class OnboardingPagerAdapter(
    private val layouts: List<Int>,
    private val onBind: (position: Int, view: View) -> Unit
) : RecyclerView.Adapter<OnboardingPagerAdapter.PageHolder>() {

    class PageHolder(view: View) : RecyclerView.ViewHolder(view)

    override fun getItemViewType(position: Int): Int = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder =
        PageHolder(
            LayoutInflater.from(parent.context).inflate(layouts[viewType], parent, false)
        )

    override fun onBindViewHolder(holder: PageHolder, position: Int) =
        onBind(position, holder.itemView)

    override fun getItemCount(): Int = layouts.size
}
