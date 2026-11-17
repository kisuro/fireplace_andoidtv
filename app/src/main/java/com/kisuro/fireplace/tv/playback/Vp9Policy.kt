package com.kisuro.fireplace.tv.playback

/**
 * MediaTek MT5898 (`c2.mtk.vp9.decoder`) intermittently crashes on 1080p60 VP9.
 * Escalate from demoting that decoder → banning all VP9 tracks (force AVC/HEVC).
 */
enum class Vp9Policy {
    /** Prefer AVC/HEVC; allow non-MTK VP9 decoders if present. */
    DEMOTE_MTK_VP9,

    /** Treat VP9 as unsupported so track selector must pick AVC/HEVC. */
    BAN_VP9,
}
