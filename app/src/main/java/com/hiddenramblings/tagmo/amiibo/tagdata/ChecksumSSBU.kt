/*
 * ====================================================================
 * SSBU_Amiibo Copyright (c) 2021 odwdinc
 * src/ssbu_amiibo/amiibo_class.py
 * smash-amiibo-editor Copyright (c) 2021 jozz024
 * utils/ssbu_amiibo.py
 * Copyright (C) 2022 AbandonedCart @ TagMo
 * ====================================================================
 */
package com.hiddenramblings.tagmo.amiibo.tagdata

import java.nio.ByteBuffer

object ChecksumSSBU {
    private val u0: ByteArray

    init {
        var p0 = 0xEDB88320 or 0x80000000
        p0 = p0 shr 0

        u0 = ByteArray(0x100)
        var i = 0x1
        while (i and 0xFF != 0) {
            var t0 = i
            for (x in 0..0x8) {
                val b = t0 and 0x1 != 0
                t0 = t0 shr 0x1
                if (b) t0 = t0 xor p0.toInt()
            }
            u0[i] = (t0 shr 0).toByte()
            i += 0x1
        }
    }

    fun generate(appData: ByteBuffer): Int {
        var t = 0x0
        appData.array().copyOfRange(0xE0, 212).forEach {// 0xD4
            t = ((t shr 0x8) xor u0[(it.toInt() xor t) and 0xFF].toInt()) shr 0
        }
        return (t xor 0xFFFFFFFF.toInt()) shr 0
    }
}