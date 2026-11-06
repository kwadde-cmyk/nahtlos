package de.jenz.nahtlos.core.playback

import androidx.media3.session.CommandButton
import de.jenz.nahtlos.core.R

/**
 * Tasten der Wiedergabe.
 *
 * Im Auto beanspruchen Skip- und Kapitel-Icons sonst den Platz links und rechts von Play
 * und verschwinden aus der zweiten Leiste. Boost belegt den Nebenplatz von Play
 * (forward secondary) und bleibt zusätzlich erste Taste der Überlaufleiste.
 */
data class CarButton(
    val name: String,
    val action: String,
    val icon: Int,
    val slots: IntArray,
    val iconRes: Int = 0,
)

object CarControls {
    fun buttons(payments: Boolean, automotive: Boolean): List<CarButton> {
        if (!automotive) return phoneButtons(payments)
        val overflow = intArrayOf(CommandButton.SLOT_OVERFLOW)
        return buildList {
            if (payments) {
                add(
                    CarButton(
                        name = "Boost",
                        action = SessionActions.QUICK_BOOST,
                        icon = CommandButton.ICON_UNDEFINED,
                        slots = intArrayOf(
                            CommandButton.SLOT_FORWARD_SECONDARY,
                            CommandButton.SLOT_BACK_SECONDARY,
                            CommandButton.SLOT_OVERFLOW,
                        ),
                        iconRes = R.drawable.ic_boost,
                    ),
                )
            }
            add(CarButton("Kapitel zurück", SessionActions.CHAPTER_PREV, CommandButton.ICON_PREVIOUS, overflow))
            add(CarButton("Nächstes Kapitel", SessionActions.CHAPTER_NEXT, CommandButton.ICON_NEXT, overflow))
            add(CarButton("Langsamer", SessionActions.SPEED_DOWN, CommandButton.ICON_MINUS, overflow))
            add(CarButton("Schneller", SessionActions.SPEED_UP, CommandButton.ICON_PLUS, overflow))
            add(CarButton("Zurück", SessionActions.SKIP_BACK, CommandButton.ICON_SKIP_BACK, overflow))
            add(CarButton("Vor", SessionActions.SKIP_FORWARD, CommandButton.ICON_SKIP_FORWARD, overflow))
        }
    }

    fun boostNotice(errorMessage: String?): String {
        val text = errorMessage.orEmpty()
        val body = when {
            text.contains("läuft schon") -> "Eine Zahlung läuft schon."
            text.contains("Kein Wallet") -> "Kein Wallet auf diesem Auto. Im Stand unter Einstellungen holen."
            text.contains("Keine zahlbaren") -> "Dieser Feed hat keine Empfänger. Nichts gesendet."
            text.isBlank() -> "Unbekannter Fehler."
            else -> text
        }
        return if (body.startsWith("Boost ") || body.startsWith("Eine Zahlung")) body else "Boost nicht gesendet. $body"
    }

    private fun phoneButtons(payments: Boolean): List<CarButton> = buildList {
        add(CarButton("Zurück", SessionActions.SKIP_BACK, CommandButton.ICON_SKIP_BACK, intArrayOf()))
        add(CarButton("Vor", SessionActions.SKIP_FORWARD, CommandButton.ICON_SKIP_FORWARD, intArrayOf()))
        add(CarButton("Kapitel zurück", SessionActions.CHAPTER_PREV, CommandButton.ICON_PREVIOUS, intArrayOf()))
        add(CarButton("Nächstes Kapitel", SessionActions.CHAPTER_NEXT, CommandButton.ICON_NEXT, intArrayOf()))
        if (payments) add(CarButton("Boost", SessionActions.QUICK_BOOST, CommandButton.ICON_HEART_FILLED, intArrayOf()))
        add(CarButton("Langsamer", SessionActions.SPEED_DOWN, CommandButton.ICON_MINUS, intArrayOf()))
        add(CarButton("Schneller", SessionActions.SPEED_UP, CommandButton.ICON_PLUS, intArrayOf()))
    }
}
