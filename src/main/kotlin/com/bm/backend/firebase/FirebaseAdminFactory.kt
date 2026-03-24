package com.bm.backend.firebase

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import java.io.File
import java.io.FileInputStream

object FirebaseAdminFactory {
    fun init() {
        if (FirebaseApp.getApps().isNotEmpty()) {
            return
        }

        val explicitPath = sequenceOf(
            System.getenv("FIREBASE_SERVICE_ACCOUNT_PATH"),
            System.getenv("GOOGLE_APPLICATION_CREDENTIALS"),
            System.getProperty("firebase.service.account.path")
        ).firstOrNull { !it.isNullOrBlank() }

        val credentials = if (!explicitPath.isNullOrBlank()) {
            val credentialsFile = File(explicitPath)
            require(credentialsFile.exists()) {
                "Firebase service account file not found at: $explicitPath"
            }

            FileInputStream(credentialsFile).use { input ->
                GoogleCredentials.fromStream(input)
            }
        } else {
            GoogleCredentials.getApplicationDefault()
        }

        val optionsBuilder = FirebaseOptions.builder()
            .setCredentials(credentials)

        val projectId = System.getenv("FIREBASE_PROJECT_ID")
        if (!projectId.isNullOrBlank()) {
            optionsBuilder.setProjectId(projectId)
        }

        FirebaseApp.initializeApp(optionsBuilder.build())
    }
}
