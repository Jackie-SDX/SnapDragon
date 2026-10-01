package com.threeseeds.app.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * True when the system asks apps to minimize motion (animator duration
 * scale = 0). Consumers snap animations to their end state instead of
 * playing them — feedback survives, movement does not.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }
