package com.reelshare.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.reelshare.app.ui.ReelShareApp
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_ReelShare)
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            splashScreen.setOnExitAnimationListener { splash ->
                if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
                    splash.animate().alpha(0f).setDuration(160)
                        .withEndAction { splash.remove() }.start()
                } else splash.remove()
            }
        }
        enableEdgeToEdge()
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        setContent {
            ReelShareApp()
        }
    }
}
