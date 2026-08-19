package com.supernova.anchor.utils

/**
 * Validates what the user typed in Binary Mode's composer, BEFORE anything
 * is encoded or sent — mirrors the grammar SmsCommandProcessor.processCommand
 * enforces on the receiving end ("<prefix> <command> <password> [params]"),
 * so obviously-broken commands are caught locally instead of burning a data
 * SMS round trip just to get "unknown command" back.
 *
 * This does NOT (and cannot) validate the password itself — the sender has
 * no way to know the target device's configured password. It only checks
 * structure: is there a command word, is it a known command, are its
 * parameters well-formed.
 *
 * Driven entirely by CommandRegistry, so it needs no changes when a new
 * command is added there.
 */
object CommandValidator {

    sealed class Result {
        object Empty : Result()
        object Valid : Result()
        data class Invalid(
            val message: String,
            val example: String,
            val highlightRange: IntRange?
        ) : Result()
    }

    fun validate(raw: String, commandPrefix: String): Result {
        if (raw.isBlank()) return Result.Empty

        val trimmed = raw.trimEnd()
        val tokens = tokenize(trimmed)
        val prefixExample = commandPrefix.ifBlank { "PIN" }

        // Need at least: prefix, command
        if (tokens.size < 2) {
            return Result.Invalid(
                message = "Missing command name.",
                example = "$prefixExample locate mypassword",
                highlightRange = trimmed.length..trimmed.length
            )
        }

        val commandRange = tokens[1]
        val commandText = trimmed.substring(commandRange.first, commandRange.last + 1)
        val spec = CommandRegistry.findByName(commandText)

        if (spec == null) {
            return Result.Invalid(
                message = "Unknown command '$commandText'. Known commands: ${CommandRegistry.commandNames.joinToString(", ")}.",
                example = "$prefixExample locate mypassword",
                highlightRange = commandRange
            )
        }

        // Need: prefix, command, password
        if (tokens.size < 3) {
            return Result.Invalid(
                message = "Missing password after '${spec.name}'.",
                example = "$prefixExample ${spec.name} mypassword",
                highlightRange = trimmed.length..trimmed.length
            )
        }

        val paramTokens = tokens.drop(3)
        spec.params.forEachIndexed { index, paramSpec ->
            val tokenRange = paramTokens.getOrNull(index)

            if (tokenRange == null) {
                if (paramSpec.required) {
                    return Result.Invalid(
                        message = "Missing '${paramSpec.name}' for ${spec.name}.",
                        example = "$prefixExample ${spec.name} mypassword ${paramSpec.exampleValue}",
                        highlightRange = trimmed.length..trimmed.length
                    )
                }
                return@forEachIndexed
            }

            val value = trimmed.substring(tokenRange.first, tokenRange.last + 1)
            val valid = when {
                paramSpec.allowedValues != null -> paramSpec.allowedValues.any { it.equals(value, ignoreCase = true) }
                paramSpec.validator != null -> paramSpec.validator.invoke(value)
                else -> true
            }

            if (!valid) {
                return Result.Invalid(
                    message = "Invalid '${paramSpec.name}' — ${paramSpec.invalidHint}.",
                    example = "$prefixExample ${spec.name} mypassword ${paramSpec.exampleValue}",
                    highlightRange = tokenRange
                )
            }
        }

        return Result.Valid
    }

    /** Whitespace-delimited tokens, as char-index ranges into the original string. */
    private fun tokenize(s: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var i = 0
        while (i < s.length) {
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length) break
            val start = i
            while (i < s.length && !s[i].isWhitespace()) i++
            ranges.add(start until i)
        }
        return ranges
    }
}