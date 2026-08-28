package com.replymint.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.replymint.MainActivity
import com.replymint.R
import com.replymint.auth.SignInManager
import com.replymint.data.ModeStore
import kotlinx.coroutines.launch

/**
 * First-run tour: value prop → how it works → supported apps → Google sign-in → permissions.
 * Navigation is button-driven (swiping disabled) so sign-in can't be skipped past.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var store: ModeStore
    private lateinit var pager: ViewPager2
    private lateinit var primary: MaterialButton
    private val pageViews = HashMap<Int, View>()
    private var signingIn = false
    private var demo: TourDemoController? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        findViewById<View>(R.id.onboarding_root).padSystemBars()
        store = ModeStore(this)

        pager = findViewById(R.id.pager)
        primary = findViewById(R.id.btn_primary)

        pager.adapter = OnboardingPagerAdapter(PAGES, ::bindPage)
        pager.isUserInputEnabled = false
        TabLayoutMediator(findViewById<TabLayout>(R.id.dots), pager) { _, _ -> }.attach()
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = onPageShown(position)
        })

        primary.setOnClickListener { onPrimaryTap() }
    }

    override fun onResume() {
        super.onResume()
        pageViews[PAGE_PERMISSIONS]?.let(::updatePermissionsPage)
        pageViews[PAGE_SIGNIN]?.let(::updateSignInPage)
        updatePrimaryButton()
        if (pager.currentItem == PAGE_DEMO) demo?.start()
    }

    override fun onPause() {
        super.onPause()
        demo?.stop()
    }

    override fun onDestroy() {
        demo?.destroy()
        demo = null
        super.onDestroy()
    }

    private fun bindPage(position: Int, view: View) {
        pageViews[position] = view
        when (position) {
            PAGE_DEMO -> {
                demo?.destroy()
                demo = TourDemoController(view)
                if (pager.currentItem == PAGE_DEMO) demo?.start()
            }
            PAGE_SIGNIN -> updateSignInPage(view)
            PAGE_PERMISSIONS -> {
                view.findViewById<View>(R.id.row_overlay)
                    .setOnClickListener { PermissionsUi.requestOverlay(this) }
                view.findViewById<View>(R.id.row_a11y)
                    .setOnClickListener { PermissionsUi.openAccessibilitySettings(this) }
                updatePermissionsPage(view)
            }
        }
    }

    private fun onPageShown(position: Int) {
        if (position == PAGE_PERMISSIONS) requestRuntimePermissions()
        if (position == PAGE_DEMO) demo?.start() else demo?.stop()
        updatePrimaryButton()
    }

    private fun onPrimaryTap() {
        when {
            pager.currentItem == PAGE_SIGNIN && !store.isSignedIn -> startSignIn()
            pager.currentItem == PAGE_PERMISSIONS -> finishOnboarding()
            else -> pager.currentItem += 1
        }
    }

    private fun updatePrimaryButton() {
        primary.text = getString(
            when (pager.currentItem) {
                PAGE_SIGNIN -> if (store.isSignedIn) R.string.onb_next else R.string.onb_signin_button
                PAGE_PERMISSIONS -> R.string.onb_finish
                else -> R.string.onb_next
            }
        )
        primary.isEnabled = !signingIn
    }

    private fun startSignIn() {
        if (signingIn) return
        signingIn = true
        updatePrimaryButton()
        lifecycleScope.launch {
            val outcome = SignInManager(this@OnboardingActivity).signIn()
            signingIn = false
            when (outcome) {
                is SignInManager.Outcome.SignedIn -> {
                    pageViews[PAGE_SIGNIN]?.let(::updateSignInPage)
                    pager.currentItem = PAGE_PERMISSIONS
                }
                is SignInManager.Outcome.Cancelled -> Unit
                is SignInManager.Outcome.Failed ->
                    Toast.makeText(this@OnboardingActivity, outcome.message, Toast.LENGTH_LONG).show()
            }
            updatePrimaryButton()
        }
    }

    private fun finishOnboarding() {
        if (!store.isSignedIn) {
            Toast.makeText(this, R.string.onb_sign_in_first, Toast.LENGTH_SHORT).show()
            pager.currentItem = PAGE_SIGNIN
            return
        }
        if (!PermissionsUi.overlayGranted(this) || !PermissionsUi.accessibilityEnabled()) {
            Toast.makeText(this, R.string.toast_grant_first, Toast.LENGTH_SHORT).show()
            return
        }
        store.onboarded = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun updateSignInPage(view: View) {
        val status = view.findViewById<TextView>(R.id.signin_status)
        val email = store.email
        if (store.isSignedIn && email != null) {
            status.text = getString(R.string.signed_in_as, email)
            status.visibility = View.VISIBLE
        } else {
            status.visibility = View.GONE
        }
    }

    private fun updatePermissionsPage(view: View) {
        bindPermissionRow(
            view, R.id.overlay_state, R.id.overlay_check, PermissionsUi.overlayGranted(this)
        )
        bindPermissionRow(
            view, R.id.a11y_state, R.id.a11y_check, PermissionsUi.accessibilityEnabled()
        )
    }

    private fun bindPermissionRow(view: View, stateId: Int, checkId: Int, granted: Boolean) {
        view.findViewById<TextView>(stateId).apply {
            text = getString(if (granted) R.string.perm_granted else R.string.perm_needed)
            visibility = if (granted) View.GONE else View.VISIBLE
        }
        view.findViewById<ImageView>(checkId).visibility = if (granted) View.VISIBLE else View.GONE
    }

    private fun requestRuntimePermissions() {
        // RECORD_AUDIO for the Voice action; POST_NOTIFICATIONS so our status/error toasts are not
        // suppressed (Android/OEMs block a background app's toasts when its notifications are off).
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            add(android.Manifest.permission.RECORD_AUDIO)
        }.toTypedArray()
        permissionLauncher.launch(needed)
    }

    private companion object {
        val PAGES = listOf(
            R.layout.onboarding_page_welcome,
            R.layout.onboarding_page_demo,
            R.layout.onboarding_page_how,
            R.layout.onboarding_page_apps,
            R.layout.onboarding_page_signin,
            R.layout.onboarding_page_permissions,
        )
        const val PAGE_DEMO = 1
        const val PAGE_SIGNIN = 4
        const val PAGE_PERMISSIONS = 5
    }
}
