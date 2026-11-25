package com.ahmed.miftah.core

/**
 * First-run guide («ابدأ هنا») visibility rule.
 *
 * The guide exists for exactly one situation — the vault holds no provider key
 * at all — and disappears automatically the moment any key exists. There is no
 * manual dismiss state, so it can never be stranded on or off by accident.
 */
object Guide {
    fun shouldShow(hasAnyKey: Boolean): Boolean = !hasAnyKey

    fun shouldShow(totalKeyCount: Int): Boolean = totalKeyCount <= 0
}
