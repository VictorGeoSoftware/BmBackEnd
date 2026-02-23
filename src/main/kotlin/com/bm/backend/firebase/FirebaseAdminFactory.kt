package com.bm.backend.firebase

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

object FirebaseAdminFactory {
    fun init() {
        if (FirebaseApp.getApps().isNotEmpty()) {
            return
        }

        val optionsBuilder = FirebaseOptions.builder()
            .setCredentials(GoogleCredentials.getApplicationDefault())

        val projectId = System.getenv("FIREBASE_PROJECT_ID")
        if (!projectId.isNullOrBlank()) {
            optionsBuilder.setProjectId(projectId)
        }

        FirebaseApp.initializeApp(optionsBuilder.build())
    }
}
