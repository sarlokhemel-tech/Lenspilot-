package com.hemel.lenspilot.browser.multi

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup

/**
 * v162: lays the session tiles out so that together they fill the screen exactly — nothing cut off, nothing overlapping.
 *   1 → whole screen · 2 → top / bottom (portrait) or left / right (landscape) · 3 → one big + two · 4 → four corners
 *   5–8 → two or three per band. One tile can be maximized: it covers everything, the others keep running underneath.
 */
class TileGridLayout(context: Context) : ViewGroup(context) {

    private val tiles = ArrayList<SessionTileView>()
    var maximized: SessionTileView? = null
        private set
    var onMaximizedChanged: ((SessionTileView?) -> Unit)? = null
    private val gap = (3 * resources.displayMetrics.density).toInt()

    fun setTiles(list: List<SessionTileView>) {
        removeAllViews()
        tiles.clear()
        tiles.addAll(list)
        for (t in tiles) addView(t)
        if (maximized != null && !tiles.contains(maximized)) maximized = null
        requestLayout()
    }

    fun addTile(t: SessionTileView) {
        tiles.add(t); addView(t); requestLayout()
    }

    /** null = back to the grid. */
    fun maximize(tile: SessionTileView?) {
        maximized = tile
        tile?.bringToFront()
        for (t in tiles) t.setMaximized(t === tile)
        requestLayout()
        onMaximizedChanged?.invoke(tile)
    }

    /** Number of tiles per band. A band runs across the short side of the screen: rows in portrait, columns in landscape. */
    private fun bandsFor(n: Int): IntArray = when {
        n <= 1 -> intArrayOf(1)
        n == 2 -> intArrayOf(1, 1)
        n == 3 -> intArrayOf(1, 2)
        n == 4 -> intArrayOf(2, 2)
        n == 5 -> intArrayOf(2, 3)
        n == 6 -> intArrayOf(2, 2, 2)
        n == 7 -> intArrayOf(2, 2, 3)
        else -> intArrayOf(2, 3, 3)
    }

    private fun gridRects(n: Int, w: Int, h: Int): List<Rect> {
        val portrait = h >= w
        val bands = bandsFor(n)
        val out = ArrayList<Rect>(n)
        val g = gap / 2
        for ((bi, cnt) in bands.withIndex()) {
            for (ci in 0 until cnt) {
                val r = if (portrait) Rect(w * ci / cnt, h * bi / bands.size, w * (ci + 1) / cnt, h * (bi + 1) / bands.size)
                else Rect(w * bi / bands.size, h * ci / cnt, w * (bi + 1) / bands.size, h * (ci + 1) / cnt)
                r.inset(g, g)
                out.add(r)
            }
        }
        return out
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(suggestedMinimumHeight, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        if (w <= 0 || h <= 0 || tiles.isEmpty()) return
        val rects = gridRects(tiles.size, w, h)
        for ((i, tile) in tiles.withIndex()) {
            val rect = if (tile === maximized) Rect(0, 0, w, h) else rects[i]
            tile.measure(
                View.MeasureSpec.makeMeasureSpec(rect.width(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(rect.height(), View.MeasureSpec.EXACTLY)
            )
            tile.layout(rect.left, rect.top, rect.right, rect.bottom)
        }
    }
}
