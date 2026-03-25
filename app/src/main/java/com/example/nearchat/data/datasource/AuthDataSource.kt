package com.example.nearchat.data.datasource

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthDataSource @Inject constructor(
    private val firebaseAuth: FirebaseAuth,
    private val firebaseFirestore: FirebaseFirestore
) {
    companion object {
        private const val TAG = "AuthDataSource"
    }

    val currentUser: FirebaseUser?
        get() = firebaseAuth.currentUser

    val isLoggedIn: Boolean
        get() = currentUser != null

    /**
     * Creates a new account with Firebase Auth, then stores the user record
     * (email, displayName, passwordHash) in Firestore users/{uid}.
     */
    suspend fun signUp(email: String, displayName: String, password: String): Result<FirebaseUser> {
        return try {
            Log.d(TAG, "Starting createUserWithEmailAndPassword...")
            val result = firebaseAuth.createUserWithEmailAndPassword(email, password).await()
            Log.d(TAG, "createUserWithEmailAndPassword completed.")
            val user = result.user ?: return Result.failure(Exception("Account creation failed: result.user is null"))

            // Set display name on Firebase Auth profile
            Log.d(TAG, "Starting updateProfile...")
            val profileUpdate = UserProfileChangeRequest.Builder()
                .setDisplayName(displayName)
                .build()
            user.updateProfile(profileUpdate).await()
            Log.d(TAG, "updateProfile completed.")

            // Store user data in Firestore
            Log.d(TAG, "Starting Firestore user creation...")
            val userData = mapOf(
                "email" to email,
                "displayName" to displayName,
                "passwordHash" to hashPassword(password)
            )
            try {
                withTimeout(5000L) {
                    firebaseFirestore.collection("users")
                        .document(user.uid)
                        .set(userData)
                        .await()
                }
                Log.d(TAG, "Firestore user creation completed.")
            } catch (e: Exception) {
                Log.e(TAG, "Firestore write timed out or failed. Check if Firestore is enabled in Console.", e)
                // We still let sign-up succeed locally since the Auth account was created
            }

            Log.d(TAG, "Sign-up successful: ${user.uid}")
            Result.success(user)
        } catch (e: Exception) {
            Log.e(TAG, "Sign-up failed with exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Authenticates with Firebase Auth using email + password.
     * Only reads the current user's own record from Firestore.
     */
    suspend fun signIn(email: String, password: String): Result<Pair<FirebaseUser, String>> {
        return try {
            Log.d(TAG, "Starting signInWithEmailAndPassword...")
            val result = firebaseAuth.signInWithEmailAndPassword(email, password).await()
            Log.d(TAG, "signInWithEmailAndPassword completed.")
            val user = result.user ?: return Result.failure(Exception("Sign-in failed: result.user is null"))

            // Read only this user's own displayName from Firestore
            Log.d(TAG, "Starting Firestore read...")
            var dbDisplayName: String? = null
            try {
                withTimeout(5000L) {
                    val snapshot = firebaseFirestore.collection("users")
                        .document(user.uid)
                        .get()
                        .await()
                    dbDisplayName = snapshot.getString("displayName")
                }
                Log.d(TAG, "Firestore read completed.")
            } catch (e: Exception) {
                Log.e(TAG, "Firestore read timed out or failed. Defaulting to Auth profile name.", e)
            }

            val displayName = dbDisplayName
                ?: user.displayName
                ?: "User"

            Log.d(TAG, "Sign-in successful: ${user.uid}, displayName=$displayName")
            Result.success(Pair(user, displayName))
        } catch (e: Exception) {
            Log.e(TAG, "Sign-in failed with exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Updates the display name in both Firebase Auth profile and Firestore.
     */
    suspend fun updateDisplayName(uid: String, newName: String) {
        Log.d(TAG, "Updating display name to: $newName")
        val user = firebaseAuth.currentUser
        if (user != null) {
            val profileUpdate = UserProfileChangeRequest.Builder()
                .setDisplayName(newName)
                .build()
            user.updateProfile(profileUpdate).await()
        }
        try {
            withTimeout(5000L) {
                firebaseFirestore.collection("users")
                    .document(uid)
                    .update("displayName", newName)
                    .await()
            }
            Log.d(TAG, "Display name updated in Firestore.")
        } catch (e: Exception) {
            Log.e(TAG, "Firestore update failed", e)
        }
    }

    fun signOut() {
        firebaseAuth.signOut()
        Log.d(TAG, "Signed out")
    }

    private fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(password.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
