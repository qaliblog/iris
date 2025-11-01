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
import com.qali.iris.CameraForegroundService
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
        
        // Start background service immediately - it will handle all camera and tracking operations
        // This service will continue running even when the app is closed
        try {
            CameraForegroundService.start(this)
            android.util.Log.d("MainActivity", "Background service started")
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to start background service: ${e.message}", e)
        }
        
        activityMainBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(activityMainBinding.root)
    }
    
    override fun onResume() {
        super.onResume()
        // Ensure service is running
        if (CameraForegroundService.getInstance() == null) {
            try {
                CameraForegroundService.start(this)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to restart service: ${e.message}", e)
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // DON'T stop the service - let it continue running in background
        // The service will persist even when app is closed and handle all operations
        // User can stop it manually via notification or settings
        android.util.Log.d("MainActivity", "Activity destroyed, but service continues running")
    }

    override fun onBackPressed() {
        finish()
    }
}
