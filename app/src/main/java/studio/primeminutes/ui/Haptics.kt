package studio.primeminutes.ui

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

// One vocabulary for haptics, like iOS Support.swift `Haptics`: success, destructive, on, off.
fun HapticFeedback.confirm() = performHapticFeedback(HapticFeedbackType.Confirm)
fun HapticFeedback.reject() = performHapticFeedback(HapticFeedbackType.Reject)
fun HapticFeedback.toggleOn() = performHapticFeedback(HapticFeedbackType.ToggleOn)
fun HapticFeedback.toggleOff() = performHapticFeedback(HapticFeedbackType.ToggleOff)
