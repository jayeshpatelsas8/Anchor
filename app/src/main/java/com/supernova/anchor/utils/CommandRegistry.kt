package com.supernova.anchor.utils

/**
 * Describes one parameter a command accepts, for local (pre-send) validation
 * in Binary Mode. This is intentionally metadata-only — no UI code here.
 */
data class CommandParamSpec(
    val name: String,
    val required: Boolean,
    val allowedValues: List<String>? = null,      // fixed set, e.g. sound modes
    val validator: ((String) -> Boolean)? = null,  // custom check, e.g. trace interval range
    val invalidHint: String,
    val exampleValue: String
)

data class CommandSpec(
    val name: String,
    val description: String,
    val params: List<CommandParamSpec> = emptyList()
)

/**
 * Single source of truth for every command Anchor understands.
 *
 * Binary Mode's chat UI, its local validator, and its error messages all
 * read from this list — none of them hardcode a command name. Adding a new
 * command to the app means adding one CommandSpec entry here (and the actual
 * handling branch in SmsCommandProcessor.processCommand); nothing in the
 * ui/ package needs to change.
 *
 * Must stay in sync with the command set SmsCommandProcessor.processCommand
 * actually switches on. It intentionally does not import SmsCommandProcessor
 * to avoid a circular reference; the string constants are duplicated here on
 * purpose as the UI-facing contract.
 */
object CommandRegistry {

    val commands: List<CommandSpec> = listOf(
        CommandSpec(
            name = "locate",
            description = "Get device location"
        ),
        CommandSpec(
            name = "ring",
            description = "Ring device at max volume"
        ),
        CommandSpec(
            name = "info",
            description = "Get battery & device info"
        ),
        CommandSpec(
            name = "help",
            description = "List available commands"
        ),
        CommandSpec(
            name = "callme",
            description = "Device calls you back"
        ),
        CommandSpec(
            name = "ping",
            description = "Check if service is running"
        ),
        CommandSpec(
            name = "sound",
            description = "Change sound mode",
            params = listOf(
                CommandParamSpec(
                    name = "mode",
                    required = false,
                    allowedValues = listOf("normal", "vibrate", "silent"),
                    invalidHint = "must be normal, vibrate, or silent",
                    exampleValue = "vibrate"
                )
            )
        ),
        CommandSpec(
            name = "trace",
            description = "Continuous GPS trace (or 'stop')",
            params = listOf(
                CommandParamSpec(
                    name = "interval",
                    required = false,
                    validator = { v -> v.equals("stop", ignoreCase = true) || (v.toIntOrNull()?.let { it in 1..1440 } == true) },
                    invalidHint = "must be 1-1440 (minutes) or 'stop'",
                    exampleValue = "15"
                )
            )
        )
    )

    fun findByName(name: String): CommandSpec? =
        commands.firstOrNull { it.name.equals(name, ignoreCase = true) }

    val commandNames: List<String> get() = commands.map { it.name }
}