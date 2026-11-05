package com.untar.ultimusic.util

import androidx.annotation.StringRes
import com.untar.ultimusic.R

/** Tablas de la BD y sus columnas, en minúsculas tal cual las devuelve SQLite. */
class SqlSchema(val tables: Map<String, List<String>>) {
    companion object {
        val EMPTY = SqlSchema(emptyMap())
    }
}

/** Qué hay que hacer con lo que el usuario ha escrito en el modo SQL. */
sealed interface SqlPlan {
    /** Solo lee: se ejecuta sin pedir confirmación. */
    data object ReadOnly : SqlPlan

    /** Toca la base de datos: hay que pedir confirmación explicando [explanation]. */
    data class Write(@StringRes val explanation: Int) : SqlPlan

    /** No es un comando válido; [message] ya está listo para mostrarse. */
    data class Invalid(@StringRes val messageRes: Int, val arg: String? = null) : SqlPlan
}

/** Clasificación de comandos SQL y autocompletado guiado. Sin dependencias de Android salvo ids de strings. */
object SqlConsole {

    private val TOKEN = Regex("""'[^']*'?|"[^"]*"?|[A-Za-z_][A-Za-z0-9_.]*|\d+(?:\.\d+)?|<>|<=|>=|!=|[(),*=<>;+\-/]""")
    private val DML_WORDS = Regex("""\b(insert|update|delete|replace)\b""", RegexOption.IGNORE_CASE)

    // ---------------------------------------------------------------- clasificación

    fun plan(raw: String): SqlPlan {
        val sql = raw.trim().trimEnd(';').trim()
        if (sql.isEmpty()) return SqlPlan.Invalid(R.string.sql_error_empty)
        if (hasSemicolonOutsideQuotes(sql)) return SqlPlan.Invalid(R.string.sql_error_multi)

        val first = sql.takeWhile { !it.isWhitespace() && it != '(' }.lowercase()
        return when (first) {
            "select", "explain" -> SqlPlan.ReadOnly
            "with" -> if (DML_WORDS.containsMatchIn(stripQuoted(sql))) SqlPlan.Write(R.string.sql_confirm_other) else SqlPlan.ReadOnly
            "pragma" -> if (sql.contains('=')) SqlPlan.Write(R.string.sql_confirm_other) else SqlPlan.ReadOnly
            "insert", "replace" -> SqlPlan.Write(R.string.sql_confirm_insert)
            "update" -> SqlPlan.Write(R.string.sql_confirm_update)
            "delete" -> SqlPlan.Write(R.string.sql_confirm_delete)
            "create" -> SqlPlan.Write(R.string.sql_confirm_create)
            "alter" -> SqlPlan.Write(R.string.sql_confirm_alter)
            "drop" -> SqlPlan.Write(R.string.sql_confirm_drop)
            "vacuum", "reindex", "analyze" -> SqlPlan.Write(R.string.sql_confirm_other)
            else -> SqlPlan.Invalid(R.string.sql_error_unknown, first)
        }
    }

    /** ¿Es una sentencia de las que devuelven el número de filas tocadas? */
    fun isUpdateDelete(sql: String): Boolean {
        val first = sql.trim().takeWhile { !it.isWhitespace() }.lowercase()
        return first == "update" || first == "delete"
    }

    fun isInsert(sql: String): Boolean {
        val first = sql.trim().takeWhile { !it.isWhitespace() }.lowercase()
        return first == "insert" || first == "replace"
    }

    private fun stripQuoted(sql: String) = sql.replace(Regex("""'[^']*'|"[^"]*""""), " ")

    private fun hasSemicolonOutsideQuotes(sql: String): Boolean {
        var quote = 0.toChar()
        for (c in sql) {
            if (quote != 0.toChar()) { if (c == quote) quote = 0.toChar() }
            else if (c == '\'' || c == '"') quote = c
            else if (c == ';') return true
        }
        return false
    }

    // ---------------------------------------------------------------- autocompletado

    class Suggestion(val text: String, val hint: String? = null)

    /** [replaceStart]: dónde empieza la palabra a medio escribir que se sustituye al elegir una opción. */
    class Suggestions(val replaceStart: Int, val options: List<Suggestion>)

    private val START = listOf("SELECT", "INSERT INTO", "UPDATE", "DELETE FROM", "CREATE", "ALTER", "DROP", "PRAGMA")
    private val OPERATORS = listOf("=", "<>", "<", ">", "<=", ">=", "LIKE", "NOT", "IN", "IS", "BETWEEN")
    private val AFTER_WHERE = listOf("AND", "OR", "GROUP BY", "ORDER BY", "LIMIT")
    private val AFTER_TABLE = listOf("WHERE", "JOIN", "LEFT JOIN", "INNER JOIN", "GROUP BY", "ORDER BY", "LIMIT")
    private val SELECT_EXTRAS = listOf("*", "DISTINCT", "COUNT(*)", "MAX(", "MIN(", "SUM(", "AVG(")
    private val CLAUSE_KW = setOf(
        "select", "from", "join", "where", "and", "or", "on", "by", "having", "limit", "offset",
        "set", "values", "into", "update", "table", "delete", "insert"
    )
    private val OPERATOR_WORDS = setOf("=", "<>", "!=", "<", ">", "<=", ">=", "like", "in", "is", "between", "not")

    private class Tok(val text: String, val start: Int)

    fun suggest(textBeforeCursor: String, schema: SqlSchema): Suggestions {
        val text = textBeforeCursor
        val toks = TOKEN.findAll(text).map { Tok(it.value, it.range.first) }.toMutableList()

        // Comilla sin cerrar al final: se está escribiendo un valor literal, no hay nada que sugerir.
        toks.lastOrNull()?.let { last ->
            if ((last.text.startsWith("'") || last.text.startsWith("\"")) &&
                (last.text.length == 1 || !last.text.endsWith(last.text[0])) && last.start + last.text.length == text.length
            ) return Suggestions(text.length, emptyList())
        }

        var prefix = ""
        var replaceStart = text.length
        val last = toks.lastOrNull()
        if (last != null && last.start + last.text.length == text.length &&
            (last.text[0].isLetter() || last.text[0] == '_')
        ) {
            prefix = last.text
            replaceStart = last.start
            toks.removeAt(toks.lastIndex)
        }

        val done = toks.map { it.text }
        val raw = contextOptions(done, schema)
        val filtered = raw.filter { it.text.startsWith(prefix, ignoreCase = true) }
        // Si lo escrito ya es exactamente la única opción, no tiene sentido seguir ofreciéndola.
        val options = if (filtered.size == 1 && filtered[0].text.equals(prefix, ignoreCase = true)) emptyList() else filtered
        return Suggestions(replaceStart, options)
    }

    private fun kw(vararg words: String) = words.map { Suggestion(it) }
    private fun kw(words: List<String>) = words.map { Suggestion(it) }

    private fun tableOptions(schema: SqlSchema) = schema.tables.keys.sorted().map { Suggestion(it) }

    private fun columnOptions(tables: List<String>, schema: SqlSchema): List<Suggestion> {
        val sources = if (tables.isEmpty()) schema.tables.keys.sorted() else tables
        val seen = HashSet<String>()
        val out = ArrayList<Suggestion>()
        for (t in sources) for (c in schema.tables[t].orEmpty()) {
            if (seen.add(c)) out += Suggestion(c, if (tables.size > 1 || tables.isEmpty()) t else null)
        }
        return out
    }

    private fun tablesIn(done: List<String>, schema: SqlSchema): List<String> =
        done.map { it.lowercase() }.filter { it in schema.tables }.distinct()

    private fun contextOptions(done: List<String>, schema: SqlSchema): List<Suggestion> {
        if (done.isEmpty()) return kw(START)
        val low = done.map { it.lowercase() }
        val tables = tablesIn(done, schema)

        return when (low[0]) {
            "select" -> selectOptions(done, low, tables, schema)
            "insert" -> insertOptions(low, schema)
            "update" -> updateOptions(done, low, tables, schema)
            "delete" -> when {
                low.size == 1 -> kw("FROM")
                low.size == 2 -> tableOptions(schema)
                low.contains("where") -> whereOptions(low, tables, schema)
                else -> kw("WHERE")
            }
            "drop" -> when {
                low.size == 1 -> kw("TABLE", "INDEX", "VIEW", "TRIGGER")
                low.size == 2 && low[1] == "table" -> tableOptions(schema)
                else -> emptyList()
            }
            "create" -> if (low.size == 1) kw("TABLE", "INDEX", "VIEW", "TRIGGER") else emptyList()
            "alter" -> when {
                low.size == 1 -> kw("TABLE")
                low.size == 2 -> tableOptions(schema)
                low.size == 3 -> kw("RENAME TO", "RENAME COLUMN", "ADD COLUMN", "DROP COLUMN")
                low.size == 4 && low[3] == "column" || low.size == 5 && low[3] == "rename" -> columnOptions(tables, schema)
                else -> emptyList()
            }
            "pragma" -> when {
                low.size == 1 -> kw("table_info(", "index_list(", "foreign_key_list(", "integrity_check", "user_version", "database_list")
                low.last() == "(" -> tableOptions(schema)
                else -> emptyList()
            }
            "explain" -> if (low.size == 1) kw("QUERY PLAN", "SELECT") else if (low.size == 3 && low[1] == "query") kw("SELECT") else emptyList()
            else -> emptyList()
        }
    }

    private fun selectOptions(done: List<String>, low: List<String>, tables: List<String>, schema: SqlSchema): List<Suggestion> {
        val fromIdx = low.indexOf("from")
        if (fromIdx < 0) {
            val last = low.last()
            return when {
                last == "select" || last == "distinct" || last == "," || last == "(" ->
                    kw(SELECT_EXTRAS.filter { last == "select" || it != "DISTINCT" }) + columnOptions(emptyList(), schema)
                else -> kw("FROM")
            }
        }
        return clauseOptions(low, fromIdx, tables, schema)
    }

    /** Todo lo que va después del `FROM` de un SELECT (o del `SET` de un UPDATE usa [updateOptions]). */
    private fun clauseOptions(low: List<String>, fromIdx: Int, tables: List<String>, schema: SqlSchema): List<Suggestion> {
        val lastKwIdx = low.indices.lastOrNull { it >= fromIdx && low[it] in CLAUSE_KW } ?: fromIdx
        val kwWord = low[lastKwIdx]
        val after = low.subList(lastKwIdx + 1, low.size)

        return when (kwWord) {
            "from", "join" -> when {
                after.isEmpty() -> tableOptions(schema)
                kwWord == "join" -> kw("ON") + kw(AFTER_TABLE)
                else -> kw(AFTER_TABLE)
            }
            "on", "where", "and", "or", "having" -> expressionOptions(after, tables, schema, AFTER_WHERE)
            "by" -> {
                val order = low.getOrNull(lastKwIdx - 1) == "order"
                when {
                    after.isEmpty() || after.last() == "," -> columnOptions(tables, schema)
                    after.last() in setOf("asc", "desc") -> kw("LIMIT")
                    order -> kw("ASC", "DESC", "LIMIT")
                    else -> kw("HAVING", "ORDER BY", "LIMIT")
                }
            }
            "limit" -> if (after.isEmpty()) emptyList() else kw("OFFSET")
            else -> emptyList()
        }
    }

    private fun expressionOptions(after: List<String>, tables: List<String>, schema: SqlSchema, next: List<String>): List<Suggestion> {
        if (after.isEmpty()) return columnOptions(tables, schema)
        val last = after.last()
        return when {
            last == "is" -> kw("NULL", "NOT NULL")
            last == "not" -> kw("LIKE", "IN", "NULL")
            last in OPERATOR_WORDS -> emptyList() // toca escribir un valor
            after.size == 1 && !(last[0] == '\'' || last[0] == '"' || last[0].isDigit()) -> kw(OPERATORS)
            else -> kw(next)
        }
    }

    private fun whereOptions(low: List<String>, tables: List<String>, schema: SqlSchema): List<Suggestion> {
        val w = low.lastIndexOf("where")
        val idx = low.indices.lastOrNull { it >= w && low[it] in setOf("where", "and", "or") } ?: w
        return expressionOptions(low.subList(idx + 1, low.size), tables, schema, listOf("AND", "OR"))
    }

    private fun insertOptions(low: List<String>, schema: SqlSchema): List<Suggestion> {
        if (low.size == 1) return kw("INTO")
        if (low.size == 2) return tableOptions(schema)
        if ("values" in low || "select" in low) return emptyList()
        val table = low[2]
        val last = low.last()
        return when {
            low.size == 3 -> kw("(", "VALUES", "SELECT")
            last == "(" || last == "," -> columnOptions(listOf(table).filter { it in schema.tables }, schema)
            last == ")" -> kw("VALUES", "SELECT")
            else -> emptyList()
        }
    }

    private fun updateOptions(done: List<String>, low: List<String>, tables: List<String>, schema: SqlSchema): List<Suggestion> {
        if (low.size == 1) return tableOptions(schema)
        if (low.size == 2) return kw("SET")
        val whereIdx = low.indexOf("where")
        if (whereIdx >= 0) return whereOptions(low, tables, schema)
        val setIdx = low.indexOf("set")
        val after = low.subList(setIdx + 1, low.size)
        val last = low.last()
        return when {
            after.isEmpty() || last == "," -> columnOptions(tables, schema)
            last == "=" -> emptyList()
            after.size >= 2 && after[after.size - 2] == "=" -> kw("WHERE") + kw(",")
            else -> kw("=")
        }
    }
}
