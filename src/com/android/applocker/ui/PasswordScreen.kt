package com.android.applocker.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.applocker.security.SandboxSecurityManager

private object PasswordScreenShapes {
    val input = RoundedCornerShape(16.dp)
    val button = RoundedCornerShape(28.dp)
}

@Composable
fun PasswordScreen(
    isSetup: Boolean = false,
    promptText: String? = null,
    onUnlock: () -> Unit,
    onPasswordEntered: (String) -> Boolean,
    confirmPassword: String? = null,
    onBack: (() -> Unit)? = null,
    biometricType: SandboxSecurityManager.BiometricType = SandboxSecurityManager.BiometricType.NONE,
    onBiometricClick: () -> Unit = {},
    isExiting: Boolean = false
) {
    var enteredPassword by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    
    
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        isVisible = true
    }
    
    val headerAlpha by animateFloatAsState(
        targetValue = if (isVisible && !isExiting) 1f else 0f,
        animationSpec = tween(300)
    )
    
    val inputAlpha by animateFloatAsState(
        targetValue = if (isVisible && !isExiting) 1f else 0f,
        animationSpec = tween(300, delayMillis = 50)
    )
    
    val buttonAlpha by animateFloatAsState(
        targetValue = if (isVisible && !isExiting) 1f else 0f,
        animationSpec = tween(300, delayMillis = 100)
    )

    LaunchedEffect(confirmPassword) {
        enteredPassword = ""
        isError = false
    }
    
    val shakeOffset by animateFloatAsState(
        targetValue = if (isError) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioHighBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "shake",
        finishedListener = {
            if (isError) {
                isError = false
            }
        }
    )
    
    val shakeTranslation = if (isError) {
        kotlin.math.sin(shakeOffset * 4 * Math.PI.toFloat()) * 16f
    } else 0f
    
    fun submit() {
        if (enteredPassword.length >= 4) {
            val success = onPasswordEntered(enteredPassword)
            if (success) {
                onUnlock()
            } else {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                isError = true
                errorMessage = if (isSetup && confirmPassword != null) "Passwords mismatched. Try again or go back." else "Incorrect password"
            }
        }
    }
    
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        val maxHeight = maxHeight
        val maxWidth = maxWidth
        val isSmallHeight = maxHeight < 500.dp
        val isNarrowWidth = maxWidth < 400.dp

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.Center)
                .verticalScroll(rememberScrollState())
                .padding(if (isNarrowWidth) 16.dp else 32.dp)
                .widthIn(max = 400.dp)
        ) {
            
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer { alpha = headerAlpha }
            ) {
                if (!isSmallHeight) {
                    Text(
                        text = if (isSetup) {
                            if (confirmPassword == null) "Create Password" else "Confirm Password"
                        } else "Enter Password",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = promptText ?: if (isSetup) {
                            if (confirmPassword == null) "Choose a secure password" else "Re-enter your password"
                        } else "Enter your password to unlock private apps",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    
                    Spacer(modifier = Modifier.height(48.dp))
                } else {
                     Text(
                        text = "Enter Password",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
            
            
            OutlinedTextField(
                value = enteredPassword,
                onValueChange = { 
                    enteredPassword = it
                    if (isError) isError = false
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { 
                        translationX = shakeTranslation
                        alpha = inputAlpha
                    },
                label = { Text("Password") },
                placeholder = { Text("Enter password") },
                singleLine = true,
                visualTransformation = if (isPasswordVisible) 
                    VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { submit() }
                ),
                trailingIcon = {
                    IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                        Icon(
                            imageVector = if (isPasswordVisible) Icons.Default.Visibility 
                                          else Icons.Default.VisibilityOff,
                            contentDescription = if (isPasswordVisible) "Hide" else "Show"
                        )
                    }
                },
                isError = isError,
                supportingText = if (isError) {
                    { Text(errorMessage, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
                } else null,
                shape = PasswordScreenShapes.input,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            )
            
            Spacer(modifier = Modifier.height(if (isSmallHeight) 12.dp else 24.dp))
            
            
            Column(
                 modifier = Modifier.graphicsLayer { alpha = buttonAlpha }
            ) {
                Button(
                    onClick = { submit() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = enteredPassword.length >= 4,
                    shape = PasswordScreenShapes.button,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = if (isSetup) {
                            if (confirmPassword == null) "Continue" else "Set Password"
                        } else "Unlock",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                
                if (!isSetup && biometricType != SandboxSecurityManager.BiometricType.NONE) {
                    Spacer(modifier = Modifier.height(if (isSmallHeight) 12.dp else 24.dp))
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        IconButton(
                            onClick = onBiometricClick,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                        ) {
                            Icon(
                                imageVector = if (biometricType == SandboxSecurityManager.BiometricType.FACE) 
                                    Icons.Filled.Face else Icons.Filled.Fingerprint,
                                contentDescription = "Biometric Unlock",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
