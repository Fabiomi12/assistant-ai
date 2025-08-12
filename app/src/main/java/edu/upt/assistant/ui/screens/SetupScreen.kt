package edu.upt.assistant.ui.screens


import android.app.DatePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import edu.upt.assistant.data.SettingsKeys
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

// UI state holding onboarding inputs
data class SetupUiState(
    val username: String = "",
    val notificationsEnabled: Boolean = false,
    val selectedInterests: Set<String> = emptySet(),
    val customInterest: String = "",
    val birthday: String = ""
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : ViewModel() {
    var uiState by mutableStateOf(SetupUiState())
        private set

    // Extended interests options (more granular)
    private val interestsOptions = listOf(
        "Hiking", "Reading", "Cooking", "Music", "Travel", "Gaming",
        "Football", "Basketball", "Tennis", "Swimming", "Cycling", "Yoga",
        "Photography", "Painting", "Drawing", "Sculpture", "Writing", "Poetry",
        "Tech News", "Programming", "AI/ML", "Blockchain", "Gadgets",
        "Movies", "TV Shows", "Theater", "Comedy", "Stand-up",
        "Fitness", "Weightlifting", "Running", "CrossFit", "Pilates",
        "Foodie", "Vegan Cooking", "Baking", "Barbecue",
        "Travel", "Backpacking", "Road Trips", "Beach", "Mountains",
        "Gardening", "Astrology", "Meditation",
        "Board Games", "Chess", "Poker", "Role-Playing Games"
    )

    fun onUsernameChange(name: String) {
        uiState = uiState.copy(username = name)
    }
    fun onNotificationsToggle(enabled: Boolean) {
        uiState = uiState.copy(notificationsEnabled = enabled)
    }
    fun onInterestToggle(interest: String) {
        val newSet = uiState.selectedInterests.toMutableSet().apply {
            if (contains(interest)) remove(interest) else add(interest)
        }
        uiState = uiState.copy(selectedInterests = newSet)
    }
    fun onCustomInterestChange(text: String) {
        uiState = uiState.copy(customInterest = text)
    }
    fun onBirthdayChange(date: String) {
        uiState = uiState.copy(birthday = date)
    }

    fun persist(onComplete: () -> Unit) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[SettingsKeys.USERNAME] = uiState.username
                prefs[SettingsKeys.NOTIFICATIONS] = uiState.notificationsEnabled
                prefs[SettingsKeys.SETUP_DONE] = true
                prefs[SettingsKeys.INTERESTS] = uiState.selectedInterests.joinToString(",")
                prefs[SettingsKeys.CUSTOM_INTEREST] = uiState.customInterest
                prefs[SettingsKeys.BIRTHDAY] = uiState.birthday
            }
            onComplete()
        }
    }

    fun getInterests() = interestsOptions
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupStep1(
    username: String,
    onUsernameChange: (String) -> Unit,
    notificationsEnabled: Boolean,
    onNotificationsToggle: (Boolean) -> Unit,
    birthday: String,
    onBirthdayChange: (String) -> Unit,
    onNext: () -> Unit
) {
    val context = LocalContext.current
    var showDatePicker by remember { mutableStateOf(false) }
    val cal = Calendar.getInstance()
    val year  = cal.get(Calendar.YEAR)
    val month = cal.get(Calendar.MONTH)
    val day   = cal.get(Calendar.DAY_OF_MONTH)

    // DatePicker dialog
    if (showDatePicker) {
        DisposableEffect(Unit) {
            val dialog = DatePickerDialog(
                context,
                { _, y, m, d ->
                    onBirthdayChange(
                        String.format(
                            Locale.getDefault(),
                            "%02d/%02d/%04d",
                            d, m + 1, y
                        )
                    )
                    showDatePicker = false
                }, year, month, day
            )
            dialog.setOnCancelListener { showDatePicker = false }
            dialog.show()
            onDispose { dialog.dismiss() }
        }
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Welcome! Let’s set up your profile.",
                style = MaterialTheme.typography.headlineSmall
            )

            OutlinedTextField(
                value = username,
                onValueChange = onUsernameChange,
                label = { Text("Your Name") },
                modifier = Modifier.fillMaxWidth()
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Enable Notifications")
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = notificationsEnabled,
                    onCheckedChange = onNotificationsToggle
                )
            }

            // Birthday picker field
            Box(
                Modifier
                    .fillMaxWidth()
                    .clickable { showDatePicker = true }
            ) {
                OutlinedTextField(
                    value = birthday,
                    onValueChange = {},
                    readOnly = true,
                    enabled = false,
                    label = { Text("Birthday") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Button(
                onClick = onNext,
                enabled = username.isNotBlank() && birthday.isNotBlank(),
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Next")
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupStep2(
    interests: List<String>,
    selectedInterests: Set<String>,
    onInterestToggle: (String) -> Unit,
    customInterest: String,
    onCustomInterestChange: (String) -> Unit,
    onFinish: () -> Unit
) {
    val vm: SetupViewModel = hiltViewModel()
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Almost done! Tell us more.",
                style = MaterialTheme.typography.headlineSmall
            )

            Text("Select your interests:")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement   = Arrangement.spacedBy(8.dp),
                modifier              = Modifier.fillMaxWidth()
            ) {
                interests.forEach { item ->
                    FilterChip(
                        selected = selectedInterests.contains(item),
                        onClick  = { onInterestToggle(item) },
                        label    = { Text(item) }
                    )
                }
            }

            OutlinedTextField(
                value = customInterest,
                onValueChange = onCustomInterestChange,
                label = { Text("Other Interests") },
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = { vm.persist { onFinish() } },
                enabled = true,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Finish")
            }
        }
    }
}

@Composable
fun SetupRoute(
    onFinish: () -> Unit
) {
    val vm: SetupViewModel = hiltViewModel()
    val state = vm.uiState

    // Drive the flow with a simple step counter:
    var step by remember { mutableIntStateOf(1) }

    when (step) {
        1 -> SetupStep1(
            username             = state.username,
            onUsernameChange     = vm::onUsernameChange,
            notificationsEnabled = state.notificationsEnabled,
            onNotificationsToggle= vm::onNotificationsToggle,
            birthday             = state.birthday,
            onBirthdayChange     = vm::onBirthdayChange,
            onNext               = { step = 2 }
        )

        2 -> SetupStep2(
            interests              = vm.getInterests(),
            selectedInterests      = state.selectedInterests,
            onInterestToggle       = vm::onInterestToggle,
            customInterest         = state.customInterest,
            onCustomInterestChange = vm::onCustomInterestChange,
            onFinish = onFinish
        )
    }
}


