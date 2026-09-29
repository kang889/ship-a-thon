package com.studentmemory.copilot.services

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.studentmemory.copilot.BuildConfig

object Account {
    val configured get() = BuildConfig.FIREBASE_API_KEY.isNotBlank() && BuildConfig.FIREBASE_APP_ID.isNotBlank() && BuildConfig.FIREBASE_PROJECT_ID.isNotBlank()
    fun initialize(context: Context) {
        if (configured && FirebaseApp.getApps(context).isEmpty()) FirebaseApp.initializeApp(context,
            FirebaseOptions.Builder().setApiKey(BuildConfig.FIREBASE_API_KEY).setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID).build())
    }
    fun signIn(email: String, password: String, create: Boolean) {
        check(configured) { "Add Firebase public configuration to enable accounts." }
        val auth = FirebaseAuth.getInstance()
        Tasks.await(if (create) auth.createUserWithEmailAndPassword(email, password) else auth.signInWithEmailAndPassword(email, password))
    }
    fun token(): String {
        check(configured) { "Firebase is not configured." }
        val user = FirebaseAuth.getInstance().currentUser ?: error("Sign in first")
        return Tasks.await(user.getIdToken(false)).token ?: error("Could not obtain identity token")
    }
    fun userId(): String? = if (configured) FirebaseAuth.getInstance().currentUser?.uid else null
    fun email(): String? = if (configured) FirebaseAuth.getInstance().currentUser?.email else null
    fun signOut() { if (configured) FirebaseAuth.getInstance().signOut() }
}
