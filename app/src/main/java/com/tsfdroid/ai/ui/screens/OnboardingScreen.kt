package com.tsfdroid.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tsfdroid.ai.ui.components.AuroraBlob
import com.tsfdroid.ai.ui.components.AuroraHeroButton
import com.tsfdroid.ai.ui.theme.*
import com.tsfdroid.ai.ui.viewmodel.OnboardingViewModel

enum class OnboardingStage {
    INTRODUCTION,
    PERMISSION_PROMPT,
    PERMISSIONS
}

/**
 * Aurora onboarding (prototype screen 1): the night sky — fixed #101033 with
 * radial color washes, the morphing gradient blob hero, Bricolage display
 * title, glass chips, and white hero CTA. The profile form lives in the same
 * visual world; its labels are E2E anchors and stay verbatim.
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    var stage by remember { mutableStateOf(OnboardingStage.INTRODUCTION) }
    var showError by remember { mutableStateOf(false) }

    // A user returning to a stored profile skips straight past the introduction, as before.
    LaunchedEffect(uiState.isLoading) {
        if (!uiState.isLoading && uiState.name.isNotBlank() && uiState.dateOfBirth.isNotBlank()) {
            stage = OnboardingStage.PERMISSION_PROMPT
        }
    }

    val obColors = AuroraOnboardingColors
    Scaffold(
        containerColor = obColors.background
    ) { padding ->
        when (stage) {
            OnboardingStage.INTRODUCTION -> {
                IntroductionPanel(
                    name = uiState.name,
                    onNameChange = { viewModel.onNameChange(it); showError = false },
                    dob = uiState.dateOfBirth,
                    onDobChange = { viewModel.onDateOfBirthChange(it); showError = false },
                    showError = showError,
                    profileMustBeReentered = uiState.profileMustBeReentered,
                    storageError = uiState.storageError,
                    onContinue = {
                        if (uiState.name.isBlank() || uiState.dateOfBirth.isBlank()) {
                            showError = true
                        } else {
                            // The stage only advances once the profile is encrypted at rest.
                            viewModel.saveProfile { stage = OnboardingStage.PERMISSION_PROMPT }
                        }
                    },
                    onSkipToPermissions = { stage = OnboardingStage.PERMISSION_PROMPT },
                    modifier = Modifier.padding(padding)
                )
            }
            OnboardingStage.PERMISSION_PROMPT -> {
                PermissionPromptPanel(
                    onContinue = {
                        stage = OnboardingStage.PERMISSIONS
                    },
                    modifier = Modifier.padding(padding)
                )
            }
            OnboardingStage.PERMISSIONS -> {
                PermissionsPanel(
                    padding = padding,
                    onFinished = { viewModel.completeOnboarding(onFinished) }
                )
            }
        }
    }
}

/** Fixed night-sky palette used by onboarding in both system themes (prototype `#s-onboarding`). */
object AuroraOnboardingColors {
    val background = Color(0xFF101033)
    val ink = Color(0xFFF0EEFF)
    val sub = Color(0xFFC9C5EE)
    val lime = Color(0xFFC9F16F)
    val onLime = Color(0xFF253200)
}

/**
 * Onboarding radial color washes (prototype ::before): violet, teal and lime
 * lights over the #101033 night sky.
 */
@Composable
private fun Modifier.auroraNightWashes(): Modifier = this.then(
    Modifier.drawBehind {
        drawRect(Color(0xFF101033))
        fun wash(color: Color, cx: Float, cy: Float, r: Float) {
            val center = Offset(size.width * cx, size.height * cy)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color, Color.Transparent),
                    center = center,
                    radius = size.width * r
                ),
                center = center,
                radius = size.width * r
            )
        }
        wash(Color(0xFF786EFF).copy(alpha = 0.55f), 0.24f, 0.20f, 0.85f)
        wash(Color(0xFF00BEA5).copy(alpha = 0.34f), 0.78f, 0.12f, 0.75f)
        wash(Color(0xFFC9F16F).copy(alpha = 0.18f), 0.66f, 0.32f, 0.55f)
    }
)

@Composable
private fun OnboardingHero(modifier: Modifier = Modifier) {
    // Tall screens get the full prototype hero; short screens (test emulators
    // at 640dp) get the compact variant so both form fields stay reachable.
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val tall = maxHeight >= 700.dp
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AuroraBlob(
                size = (if (tall) 168 else 96).dp,
                iconSize = (if (tall) 84 else 48).dp
            )
        }
    }
}

/** Glass chips row (prototype .ob-chips): white 10% fills, hairline borders, lime surprise. */
@Composable
private fun OnboardingChips() {
    val ob = AuroraOnboardingColors
    Row(
        Modifier.padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OnboardingGlassChip("Plans, executes, verifies")
        OnboardingGlassChip("You confirm the sensitive ones", surprise = true)
    }
}

@Composable
private fun OnboardingGlassChip(text: String, surprise: Boolean = false) {
    val ob = AuroraOnboardingColors
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (surprise) ob.lime else Color.White.copy(alpha = 0.10f)
            )
            .border(
                1.dp,
                if (surprise) Color.Transparent else Color.White.copy(alpha = 0.22f),
                RoundedCornerShape(999.dp)
            )
            .padding(horizontal = 11.dp, vertical = 5.dp)
    ) {
        Text(
            text,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (surprise) ob.onLime else Color(0xFFEDEBFF)
        )
    }
}

@Composable
private fun IntroductionPanel(
    name: String,
    onNameChange: (String) -> Unit,
    dob: String,
    onDobChange: (String) -> Unit,
    showError: Boolean,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    profileMustBeReentered: Boolean = false,
    storageError: Boolean = false,
    onSkipToPermissions: () -> Unit = {}
) {
    val ob = AuroraOnboardingColors
    BoxWithConstraints(modifier.fillMaxSize()) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .auroraNightWashes()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        OnboardingHero()

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "TSF Droid",
            fontSize = 14.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.4.sp,
            color = ob.lime
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Your phone.\nYour rules.\nYour AI.",
            style = AuroraType.onboardingTitle,
            color = ob.ink
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "An on-device agent that plans, executes and verifies. Introduce yourself so I can serve you personally.",
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = ob.sub
        )

        // Full Aurora hero extras only where the viewport is tall enough to keep
        // both form fields above the fold (the 640dp CI emulator stays compact).
        val tallEnough = maxHeight >= 700.dp
        if (tallEnough) {
            Spacer(modifier = Modifier.height(16.dp))
            OnboardingChips()
        }

        if (profileMustBeReentered) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Your saved details could not be unlocked on this device, so they were " +
                        "not kept. Nothing was stored unencrypted - please enter them again.",
                color = Color(0xFFFFB4AB),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("What should I call you?", color = ob.sub) },
            placeholder = { Text("Enter your name", color = ob.sub.copy(alpha = 0.6f)) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ob.lime,
                unfocusedBorderColor = Color(0xFF3D3E5C),
                focusedLabelColor = ob.ink,
                unfocusedLabelColor = ob.sub,
                focusedTextColor = ob.ink,
                unfocusedTextColor = ob.ink,
                cursorColor = ob.lime
            ),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        var showDatePicker by remember { mutableStateOf(false) }

        OutlinedTextField(
            value = dob,
            onValueChange = onDobChange,
            label = { Text("When is your birthday?", color = ob.sub) },
            placeholder = { Text("e.g. MM/DD/YYYY", color = ob.sub.copy(alpha = 0.6f)) },
            trailingIcon = {
                IconButton(onClick = { showDatePicker = true }) {
                    Icon(
                        imageVector = Icons.Default.DateRange,
                        contentDescription = "Pick your birthday",
                        tint = ob.ink
                    )
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ob.lime,
                unfocusedBorderColor = Color(0xFF3D3E5C),
                focusedLabelColor = ob.ink,
                unfocusedLabelColor = ob.sub,
                focusedTextColor = ob.ink,
                unfocusedTextColor = ob.ink,
                cursorColor = ob.lime
            ),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onContinue() }),
            modifier = Modifier.fillMaxWidth()
        )

        if (showDatePicker) {
            val datePickerState = rememberDatePickerState(
                initialSelectedDateMillis = parseDobToUtcMillis(dob),
                yearRange = 1900..java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            )
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    TextButton(
                        onClick = {
                            datePickerState.selectedDateMillis?.let { millis ->
                                onDobChange(formatUtcMillisAsDob(millis))
                            }
                            showDatePicker = false
                        },
                        enabled = datePickerState.selectedDateMillis != null
                    ) { Text("OK", color = ob.ink, fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    TextButton(onClick = { showDatePicker = false }) {
                        Text("Cancel", color = ob.sub)
                    }
                }
            ) {
                DatePicker(state = datePickerState)
            }
        }

        if (showError) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Please enter both your name and birth date.",
                color = Color(0xFFFFB4AB),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (storageError) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Your details could not be saved securely. Please try again.",
                color = Color(0xFFFFB4AB),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        AuroraHeroButton(
            text = "Let's Go",
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
            container = Color.White,
            contentColor = Color(0xFF12124E)
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Ghost secondary CTA (prototype .ob-cta .btn.ghost) — tall screens only
        if (tallEnough) {
            Box(
            Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(RoundedCornerShape(30.dp))
                .border(1.5.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(30.dp))
                .clickable { onSkipToPermissions() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Review permissions first",
                color = Color(0xFFE7E4FF),
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold
            )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
    }
}

/** Parses a typed MM/DD/YYYY value into UTC millis for the picker, or null if not parseable. */
private fun parseDobToUtcMillis(dob: String): Long? = runCatching {
    val format = java.text.SimpleDateFormat("MM/dd/yyyy", java.util.Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
        isLenient = false
    }
    format.parse(dob.trim())?.time
}.getOrNull()

/** Formats picker UTC millis as the MM/DD/YYYY string the rest of onboarding expects. */
private fun formatUtcMillisAsDob(millis: Long): String {
    val format = java.text.SimpleDateFormat("MM/dd/yyyy", java.util.Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }
    return format.format(java.util.Date(millis))
}

@Composable
fun PermissionPromptPanel(
    onContinue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ob = AuroraOnboardingColors
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 26.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        OnboardingHero()

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Permissions Setup",
            style = AuroraType.bigTitle,
            color = ob.ink
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Let's give me permission so I can serve you well",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = ob.lime,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "To allow me to interact with your device, run commands, list files, and operate system features, some standard Android permissions are required.",
            fontSize = 14.sp,
            lineHeight = 21.sp,
            color = ob.sub,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(modifier = Modifier.height(44.dp))

        AuroraHeroButton(
            text = "Grant Permissions",
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
            container = Color.White,
            contentColor = Color(0xFF12124E)
        )
    }
}
