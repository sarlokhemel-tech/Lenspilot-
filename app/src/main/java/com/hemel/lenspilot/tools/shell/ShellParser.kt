package com.hemel.lenspilot.tools.shell

/**
 * শেল লাইন পার্সার: কোটেশন ('...' ও "..."), ব্যাকস্ল্যাশ-এস্কেপ, `;` ও `&&` চেইন, নতুন-লাইন (= `;`)।
 * পাইপ, রিডাইরেক্ট, ব্যাকগ্রাউন্ড (&) সমর্থিত নয় — স্পষ্ট বার্তা দেয়।
 */
internal object ShellParser {

    enum class Join { SEQ, AND, END }

    class Segment(val words: List<String>, val join: Join)

    sealed class Result {
        class Ok(val segments: List<Segment>) : Result()
        class Unsupported(val message: String) : Result()
        class Error(val message: String) : Result()
    }

    private const val MSG_PIPE = "Pipes (|) are not supported — run each command separately."
    private const val MSG_REDIR = "Redirects (>, >>, <) are not supported — use Storage to write to files."
    private const val MSG_BG = "Background (&) is not supported — commands run one after another."

    fun parse(line: String): Result {
        val segs = ArrayList<Segment>()
        val words = ArrayList<String>()
        val cur = StringBuilder()
        var inWord = false
        var pendingAnd = false
        var i = 0
        val n = line.length

        while (i < n) {
            val c = line[i]
            when {
                c == '\\' -> {
                    if (i + 1 < n) {
                        cur.append(line[i + 1])
                        i++
                    } else {
                        cur.append('\\')
                    }
                    inWord = true
                }
                c == '\'' -> {
                    val end = line.indexOf('\'', i + 1)
                    if (end < 0) return Result.Error("Unterminated ' quote.")
                    cur.append(line, i + 1, end)
                    inWord = true
                    i = end
                }
                c == '"' -> {
                    var j = i + 1
                    var closed = false
                    while (j < n) {
                        val d = line[j]
                        if (d == '\\' && j + 1 < n && (line[j + 1] == '"' || line[j + 1] == '\\' || line[j + 1] == '$' || line[j + 1] == '`')) {
                            cur.append(line[j + 1])
                            j += 2
                            continue
                        }
                        if (d == '"') {
                            closed = true
                            break
                        }
                        cur.append(d)
                        j++
                    }
                    if (!closed) return Result.Error("Unterminated \" quote.")
                    inWord = true
                    i = j
                }
                c == ' ' || c == '\t' || c == '\r' -> {
                    if (inWord) {
                        words.add(cur.toString())
                        cur.setLength(0)
                        inWord = false
                    }
                }
                c == '#' && !inWord -> {
                    // মন্তব্য: লাইনের শেষ পর্যন্ত বাদ
                    while (i + 1 < n && line[i + 1] != '\n') i++
                }
                c == '\n' || c == ';' -> {
                    if (inWord) {
                        words.add(cur.toString())
                        cur.setLength(0)
                        inWord = false
                    }
                    if (words.isEmpty()) {
                        if (c == ';') return Result.Error("syntax error: no command before ';'.")
                        if (pendingAnd) {
                            // && এর পর নতুন-লাইন: পরের লাইনের কমান্ড জুড়ে নেওয়া হয় — pendingAnd রেখে এগোই
                        }
                    } else {
                        segs.add(Segment(ArrayList(words), Join.SEQ))
                        words.clear()
                        pendingAnd = false
                    }
                }
                c == '&' -> {
                    if (i + 1 < n && line[i + 1] == '&') {
                        if (inWord) {
                            words.add(cur.toString())
                            cur.setLength(0)
                            inWord = false
                        }
                        if (words.isEmpty()) return Result.Error("syntax error: no command before '&&'.")
                        segs.add(Segment(ArrayList(words), Join.AND))
                        words.clear()
                        pendingAnd = true
                        i++
                    } else if (i + 1 < n && line[i + 1] == '>') {
                        return Result.Unsupported(MSG_REDIR)
                    } else {
                        return Result.Unsupported(MSG_BG)
                    }
                }
                c == '|' -> return Result.Unsupported(MSG_PIPE)
                c == '>' || c == '<' -> return Result.Unsupported(MSG_REDIR)
                else -> {
                    cur.append(c)
                    inWord = true
                }
            }
            i++
        }

        if (inWord) {
            words.add(cur.toString())
            cur.setLength(0)
        }
        if (words.isNotEmpty()) {
            segs.add(Segment(ArrayList(words), Join.END))
        } else if (pendingAnd) {
            return Result.Error("syntax error: expected a command after '&&'.")
        }
        return Result.Ok(segs)
    }
}
