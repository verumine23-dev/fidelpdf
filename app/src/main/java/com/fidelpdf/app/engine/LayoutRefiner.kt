package com.fidelpdf.app.engine

import android.content.res.AssetManager
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Finition de la mise en page : comme un maquettiste qui aligne tout à la règle.
 *  - les mots d'une même ligne partagent la même taille et la même ligne de base ;
 *  - les tailles quasi identiques du document sont unifiées (corps de texte, titres) ;
 *  - les débuts de ligne alignés sur une même colonne sont mis exactement à la même abscisse ;
 *  - les traits proches sont alignés sur une même coordonnée et se rejoignent aux jonctions.
 */
object LayoutRefiner {

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
    }

    fun refineTexts(texts: List<TextItem>, assets: AssetManager): List<TextItem> {
        if (texts.isEmpty()) return texts
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val sizes = FloatArray(texts.size)
        val bases = FloatArray(texts.size)
        for (i in texts.indices) {
            val t = texts[i]
            val p = TextFit.place(t.text, t.box, Fonts.get(assets, t.font), paint)
            sizes[i] = p.size
            bases[i] = p.baseline
        }

        val byLine = texts.indices.groupBy { texts[it].line }
        val lineSize = HashMap<Int, Float>()
        val lineBase = HashMap<Int, Float>()
        for ((ln, idx) in byLine) {
            lineSize[ln] = median(idx.map { sizes[it] })
            lineBase[ln] = median(idx.map { bases[it] })
        }

        // Unification des tailles voisines (à 6 % près) sur tout le document
        val orderedLines = lineSize.keys.sortedBy { lineSize[it] ?: 0f }
        val snappedSize = HashMap<Int, Float>()
        var start = 0
        while (start < orderedLines.size) {
            val s0 = lineSize[orderedLines[start]] ?: 0f
            var end = start
            while (end + 1 < orderedLines.size && (lineSize[orderedLines[end + 1]] ?: 0f) <= s0 * 1.06f) end++
            val m = median((start..end).map { lineSize[orderedLines[it]] ?: 0f })
            for (k in start..end) snappedSize[orderedLines[k]] = m
            start = end + 1
        }

        // Alignement des débuts de ligne : colonnes à moins de 2,5 px => même abscisse
        val firsts = byLine.values.map { idx -> idx.minByOrNull { texts[it].box.left } ?: idx[0] }
            .sortedBy { texts[it].box.left }
        val delta = HashMap<Int, Float>()
        var cs = 0
        while (cs < firsts.size) {
            val l0 = texts[firsts[cs]].box.left
            var ce = cs
            while (ce + 1 < firsts.size && texts[firsts[ce + 1]].box.left - l0 <= 2.5f) ce++
            if (ce > cs) {
                val m = median((cs..ce).map { texts[firsts[it]].box.left })
                for (k in cs..ce) delta[firsts[k]] = m - texts[firsts[k]].box.left
            }
            cs = ce + 1
        }

        return texts.mapIndexed { i, t ->
            val d = delta[i] ?: 0f
            val b = RectF(t.box.left + d, t.box.top, t.box.right + d, t.box.bottom)
            t.copy(
                box = b,
                fixedSize = snappedSize[t.line] ?: 0f,
                fixedBaseline = lineBase[t.line] ?: 0f
            )
        }
    }

    /** Valeurs proches (à [tol] près) ramenées à la médiane de leur groupe. */
    private fun cluster(values: List<Float>, tol: Float): FloatArray {
        val out = FloatArray(values.size)
        val order = values.indices.sortedBy { values[it] }
        var s = 0
        while (s < order.size) {
            val v0 = values[order[s]]
            var e = s
            while (e + 1 < order.size && values[order[e + 1]] - v0 <= tol) e++
            val m = median((s..e).map { values[order[it]] })
            for (k in s..e) out[order[k]] = m
            s = e + 1
        }
        return out
    }

    fun refineRules(rules: List<RuleLine>): List<RuleLine> {
        if (rules.isEmpty()) return rules
        val hs = rules.filter { abs(it.x2 - it.x1) >= abs(it.y2 - it.y1) }
        val vs = rules.filter { abs(it.x2 - it.x1) < abs(it.y2 - it.y1) }

        val hy = cluster(hs.map { it.y1 }, 2f)
        val h1 = hs.mapIndexed { i, r -> r.copy(y1 = hy[i], y2 = hy[i]) }
        val vx = cluster(vs.map { it.x1 }, 2f)
        val v1 = vs.mapIndexed { i, r -> r.copy(x1 = vx[i], x2 = vx[i]) }

        val tol = 8f
        val h2 = h1.map { r ->
            var x1 = r.x1
            var x2 = r.x2
            var d1 = tol + 1f
            var d2 = tol + 1f
            for (v in v1) {
                val top = min(v.y1, v.y2) - tol
                val bottom = max(v.y1, v.y2) + tol
                if (r.y1 < top || r.y1 > bottom) continue
                val a = abs(r.x1 - v.x1)
                if (a <= tol && a < d1) {
                    d1 = a
                    x1 = v.x1
                }
                val b = abs(r.x2 - v.x1)
                if (b <= tol && b < d2) {
                    d2 = b
                    x2 = v.x1
                }
            }
            r.copy(x1 = x1, x2 = x2)
        }
        val v2 = v1.map { r ->
            var y1 = r.y1
            var y2 = r.y2
            var d1 = tol + 1f
            var d2 = tol + 1f
            for (h in h1) {
                val left = min(h.x1, h.x2) - tol
                val right = max(h.x1, h.x2) + tol
                if (r.x1 < left || r.x1 > right) continue
                val a = abs(r.y1 - h.y1)
                if (a <= tol && a < d1) {
                    d1 = a
                    y1 = h.y1
                }
                val b = abs(r.y2 - h.y1)
                if (b <= tol && b < d2) {
                    d2 = b
                    y2 = h.y1
                }
            }
            r.copy(y1 = y1, y2 = y2)
        }
        return h2 + v2
    }
}
