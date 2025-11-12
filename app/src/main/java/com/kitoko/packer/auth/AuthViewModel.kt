package com.kitoko.packer.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface AuthState {
    data object SignedOut : AuthState
    data class SignedIn(val email: String) : AuthState
    data object Loading : AuthState
    data class Error(val message: String) : AuthState
}

class AuthViewModel : ViewModel() {
    private val auth = try { FirebaseAuth.getInstance() } catch (_: Throwable) { null }

    private val _state = MutableStateFlow<AuthState>(AuthState.SignedOut)
    val state: StateFlow<AuthState> = _state

    fun signIn(email: String, password: String) {
        val a = auth
        if (a == null) {
            // Offline fallback: treat as signed in
            _state.value = AuthState.SignedIn(email)
            return
        }
        _state.value = AuthState.Loading
        a.signInWithEmailAndPassword(email, password)
            .addOnSuccessListener { _state.value = AuthState.SignedIn(email) }
            .addOnFailureListener { e -> _state.value = AuthState.Error(e.message ?: "Sign in failed") }
    }
}
