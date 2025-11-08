/*
 * Copyright 2023 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.qali.iris

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.qali.iris.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var activityMainBinding: ActivityMainBinding
    private val viewModel : MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Hide action bar and make full screen
        supportActionBar?.hide()
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        
        // Keep screen on to prevent activity suspension
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Camera and MediaPipe are now handled by EyeTrackingAccessibilityService
        // No need to start a separate foreground service - accessibility service handles everything
        
        activityMainBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(activityMainBinding.root)
    }
    
    override fun onResume() {
        super.onResume()
        // Camera and MediaPipe are handled by EyeTrackingAccessibilityService
        // User needs to enable accessibility service in system settings
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // EyeTrackingAccessibilityService continues running when enabled
        // User can disable it in system accessibility settings
        android.util.Log.d("MainActivity", "Activity destroyed, accessibility service continues if enabled")
    }

    override fun onBackPressed() {
        finish()
    }
}
