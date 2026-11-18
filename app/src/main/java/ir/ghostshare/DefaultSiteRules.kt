package ir.ghostshare

object DefaultSiteRules {
    private val R1 = SiteRule(strip = setOf("federated_search_id", "search_type", "source", "source_impression_id"))
    private val R2 = SiteRule(strip = setOf("aff_fcid", "aff_fsk", "aff_platform", "aff_request_id", "aff_short_key", "aff_trace_key", "algo_exp_id", "algo_expid", "algo_pvid", "btsid", "curpageloguid", "gps-id", "mall_affr", "pdp_npi", "pvid", "scm*", "spm", "terminal_id", "utparam", "utparam-url", "ws_ab_test"))
    private val R3 = SiteRule(strip = setOf("_encoding", "aaxitk", "ascsubtag", "camp", "colid", "coliid", "content-id", "creative", "creativeasin", "crid", "dchild", "dib", "dib_tag", "linkcode", "ms3_c", "pd_rd_*", "pf_rd_*", "qid", "qualifier", "ref_", "refrid", "rnid", "skiptwisterog", "smid", "social_share", "spia", "sprefix", "sr", "srs", "starsleft", "tag", "th"))
    private val R4 = SiteRule(strip = setOf("afid", "ct", "ign-itscg", "ign-itsct", "ign-mpt", "itscg", "itsct", "pt"))
    private val R5 = SiteRule(strip = setOf("at_*", "ns_campaign", "ns_fee", "ns_linkname", "ns_mchannel", "ns_source"))
    private val R6 = SiteRule(strip = setOf("qp", "qs", "sc", "sk", "sp"))
    private val R7 = SiteRule(strip = setOf("in_source", "leadsource", "sref", "srnd"))
    private val R8 = SiteRule(keep = setOf("cid", "eid"))
    private val R9 = SiteRule(strip = setOf("psf_variant", "share_intent", "source_location"))
    private val R10 = SiteRule(strip = setOf("__source"))
    private val R11 = SiteRule(strip = setOf("hpt", "source"))
    private val R12 = SiteRule(strip = setOf("siteid"))
    private val R13 = SiteRule(strip = setOf("_from", "_trkparms", "_trksid", "amdata", "campid", "hash", "itmmeta", "itmprp", "mkcid", "mkevt", "mkrid", "ssspo", "sssrc", "ssuid"))
    private val R14 = SiteRule(strip = setOf("click_key", "click_sum", "organic_search_click"))
    private val R15 = SiteRule(strip = setOf("__cft__*", "__tn__", "__xts__*", "_rdr", "comment_tracking", "dti", "eav", "extid", "fref", "ftentidentifier", "hc_*", "idorvanity", "ls_ref", "mibextid", "paipv", "rdc", "rdid", "rdr", "ref", "referral_code", "referral_story_type", "refsrc", "tracking", "video_source", "wtsid"))
    private val R16 = SiteRule(strip = setOf("fbclid", "mibextid", "ref", "refsrc"))
    private val R17 = SiteRule(strip = setOf("context_referrer", "funnel", "ref_ctx_id"))
    private val R18 = SiteRule(strip = setOf("sh"))
    private val R19 = SiteRule(strip = setOf("email_source", "email_token", "reference_location"))
    private val R20 = SiteRule(strip = setOf("from_search", "from_srp", "qid"))
    private val R21 = SiteRule(strip = setOf("aqs", "bih", "biw", "cad", "cshid", "dpr", "ei", "esrc", "fbs", "g_ep", "gfe_*", "gs_*", "gs_lcrp", "gs_lp", "gs_ssp", "gws_rd", "ictx", "iflsig", "oq", "pcampaignid", "rlz", "sa", "sca_esv", "sca_upv", "sei", "sourceid", "sxsrf", "uact", "usg", "ved"))
    private val R22 = SiteRule(strip = setOf("pf_rd_*", "ref_"))
    private val R23 = SiteRule(keep = setOf("img_index", "next", "q", "story_media_id"), strip = setOf("*"))
    private val R24 = SiteRule(keep = setOf("e", "u"), strip = setOf("ig*", "igsh", "igshid", "igsi", "obrf", "shid", "sktn", "slof", "source", "src", "stkn", "xmt"))
    private val R25 = SiteRule(strip = setOf("courseclaim", "lici", "lipi", "original_referer", "refid", "trackingid", "trk", "trk*"))
    private val R26 = SiteRule(strip = setOf("source"))
    private val R27 = SiteRule(strip = setOf("tctx", "trackid"))
    private val R28 = SiteRule(strip = setOf("smid"))
    private val R29 = SiteRule(strip = setOf("dlsi", "referral", "sp_cid"))
    private val R30 = SiteRule(strip = setOf("epik", "invite_code", "nic", "sender", "sfo"))
    private val R31 = SiteRule(strip = setOf("pcampaignid", "referrer"))
    private val R32 = SiteRule(strip = setOf("funneluuid"))
    private val R33 = SiteRule(strip = setOf("share", "srid"))
    private val R34 = SiteRule(strip = setOf("\$3p", "\$deep_link", "\$original_url", "correlation_id", "rdt", "rdt_cid", "ref", "ref_campaign", "ref_source", "share_id"))
    private val R35 = SiteRule(strip = setOf("sc_referrer", "sc_ua"))
    private val R36 = SiteRule(strip = setOf("c", "p", "ref", "si"))
    private val R37 = SiteRule(strip = setOf("dlsi", "nd", "pi", "pt", "referral", "si", "sp_cid"))
    private val R38 = SiteRule(strip = setOf("nd", "pi", "pt", "si"))
    private val R39 = SiteRule(strip = setOf("snr"))
    private val R40 = SiteRule(strip = setOf("cn", "s", "src", "t"))
    private val R41 = SiteRule(strip = setOf("adgroup", "afid", "cpng", "device", "dfa", "fndsrc", "lid", "lnm", "network", "ref", "targetid"))
    private val R42 = SiteRule(strip = setOf("sr_share"))
    private val R43 = SiteRule(strip = setOf("cmp", "intcmp"))
    private val R44 = SiteRule(strip = setOf("_d", "_r", "_t", "checksum", "embed_source", "enter_method", "is_from_webapp", "is_fromwebapp", "preview_pb", "refer", "referer_url", "referer_video_id", "sender_device", "share_app_id", "share_app_name", "share_author_id", "share_iid", "share_item_id", "share_link_id", "share_region", "social_share_type", "source", "timestamp", "trackparams", "tt_from", "u_code", "ug_btm", "user_id", "web_id"))
    private val R45 = SiteRule(strip = setOf("tt_content", "tt_medium"))
    private val R46 = SiteRule(strip = setOf("uclick_id"))
    private val R47 = SiteRule(strip = setOf("marketing-visitor-id", "sub-campaign"))
    private val R48 = SiteRule(strip = setOf("athancid", "athbdg", "athcpid", "athena", "athguid", "athieid", "athpgid", "athznid", "u1"))
    private val R49 = SiteRule(strip = setOf("itid"))
    private val R50 = SiteRule(strip = setOf("wprov", "wpsrc"))
    private val R51 = SiteRule(strip = setOf("intcid"))
    private val R52 = SiteRule(strip = setOf("ab_channel", "cbrd", "feature", "gclid", "kw", "pp", "si", "themerefresh"))

    val ALL: Map<String, SiteRule> = mapOf(
        "airbnb.at" to R1,
        "airbnb.be" to R1,
        "airbnb.ca" to R1,
        "airbnb.ch" to R1,
        "airbnb.co.in" to R1,
        "airbnb.co.kr" to R1,
        "airbnb.co.nz" to R1,
        "airbnb.co.uk" to R1,
        "airbnb.com" to R1,
        "airbnb.com.ar" to R1,
        "airbnb.com.au" to R1,
        "airbnb.com.br" to R1,
        "airbnb.com.mx" to R1,
        "airbnb.cz" to R1,
        "airbnb.de" to R1,
        "airbnb.dk" to R1,
        "airbnb.es" to R1,
        "airbnb.fi" to R1,
        "airbnb.fr" to R1,
        "airbnb.gr" to R1,
        "airbnb.ie" to R1,
        "airbnb.it" to R1,
        "airbnb.jp" to R1,
        "airbnb.nl" to R1,
        "airbnb.no" to R1,
        "airbnb.pl" to R1,
        "airbnb.pt" to R1,
        "airbnb.se" to R1,
        "aliexpress.com" to R2,
        "aliexpress.us" to R2,
        "amazon.ae" to R3,
        "amazon.ca" to R3,
        "amazon.co.jp" to R3,
        "amazon.co.uk" to R3,
        "amazon.co.za" to R3,
        "amazon.com" to R3,
        "amazon.com.au" to R3,
        "amazon.com.be" to R3,
        "amazon.com.br" to R3,
        "amazon.com.mx" to R3,
        "amazon.com.tr" to R3,
        "amazon.de" to R3,
        "amazon.eg" to R3,
        "amazon.es" to R3,
        "amazon.fr" to R3,
        "amazon.ie" to R3,
        "amazon.in" to R3,
        "amazon.it" to R3,
        "amazon.nl" to R3,
        "amazon.pl" to R3,
        "amazon.sa" to R3,
        "amazon.se" to R3,
        "amazon.sg" to R3,
        "apple.com" to R4,
        "bbc.co.uk" to R5,
        "bbc.com" to R5,
        "bing.com" to R6,
        "bloomberg.com" to R7,
        "calendar.google.com" to R8,
        "change.org" to R9,
        "cnbc.com" to R10,
        "cnn.com" to R11,
        "coursera.org" to R12,
        "ebay.at" to R13,
        "ebay.ca" to R13,
        "ebay.ch" to R13,
        "ebay.co.nz" to R13,
        "ebay.co.uk" to R13,
        "ebay.com" to R13,
        "ebay.com.au" to R13,
        "ebay.com.be" to R13,
        "ebay.com.hk" to R13,
        "ebay.com.my" to R13,
        "ebay.com.sg" to R13,
        "ebay.de" to R13,
        "ebay.es" to R13,
        "ebay.fr" to R13,
        "ebay.ie" to R13,
        "ebay.it" to R13,
        "ebay.nl" to R13,
        "ebay.ph" to R13,
        "ebay.pl" to R13,
        "etsy.com" to R14,
        "facebook.com" to R15,
        "fb.com" to R16,
        "fb.me" to R16,
        "fb.watch" to R16,
        "fiverr.com" to R17,
        "forbes.com" to R18,
        "github.com" to R19,
        "goodreads.com" to R20,
        "google.ae" to R21,
        "google.at" to R21,
        "google.be" to R21,
        "google.bg" to R21,
        "google.by" to R21,
        "google.ca" to R21,
        "google.ch" to R21,
        "google.cl" to R21,
        "google.co.id" to R21,
        "google.co.il" to R21,
        "google.co.in" to R21,
        "google.co.jp" to R21,
        "google.co.ke" to R21,
        "google.co.kr" to R21,
        "google.co.nz" to R21,
        "google.co.th" to R21,
        "google.co.uk" to R21,
        "google.co.ve" to R21,
        "google.co.za" to R21,
        "google.com" to R21,
        "google.com.ar" to R21,
        "google.com.au" to R21,
        "google.com.bd" to R21,
        "google.com.br" to R21,
        "google.com.co" to R21,
        "google.com.ec" to R21,
        "google.com.eg" to R21,
        "google.com.hk" to R21,
        "google.com.mx" to R21,
        "google.com.my" to R21,
        "google.com.ng" to R21,
        "google.com.pe" to R21,
        "google.com.ph" to R21,
        "google.com.pk" to R21,
        "google.com.sa" to R21,
        "google.com.sg" to R21,
        "google.com.tr" to R21,
        "google.com.tw" to R21,
        "google.com.ua" to R21,
        "google.com.vn" to R21,
        "google.cz" to R21,
        "google.de" to R21,
        "google.dk" to R21,
        "google.ee" to R21,
        "google.es" to R21,
        "google.fi" to R21,
        "google.fr" to R21,
        "google.gr" to R21,
        "google.hu" to R21,
        "google.ie" to R21,
        "google.it" to R21,
        "google.kz" to R21,
        "google.lk" to R21,
        "google.lt" to R21,
        "google.lv" to R21,
        "google.nl" to R21,
        "google.no" to R21,
        "google.pl" to R21,
        "google.pt" to R21,
        "google.ro" to R21,
        "google.rs" to R21,
        "google.ru" to R21,
        "google.se" to R21,
        "google.si" to R21,
        "google.sk" to R21,
        "imdb.com" to R22,
        "instagram.com" to R23,
        "l.instagram.com" to R24,
        "l.threads.com" to R24,
        "l.threads.net" to R24,
        "linkedin.com" to R25,
        "medium.com" to R26,
        "netflix.com" to R27,
        "nytimes.com" to R28,
        "open.spotify.com" to R29,
        "pin.it" to R30,
        "pinterest.com" to R30,
        "play.google.com" to R31,
        "quizlet.com" to R32,
        "quora.com" to R33,
        "redd.it" to R34,
        "reddit.com" to R34,
        "snapchat.com" to R35,
        "soundcloud.com" to R36,
        "spotify.com" to R37,
        "spotify.link" to R38,
        "steampowered.com" to R39,
        "t.co" to R40,
        "target.com" to R41,
        "techcrunch.com" to R42,
        "theguardian.com" to R43,
        "threads.com" to R23,
        "threads.net" to R23,
        "tiktok.com" to R44,
        "tumblr.com" to R26,
        "twitch.tv" to R45,
        "twitter.com" to R40,
        "uber.com" to R46,
        "ubereats.com" to R47,
        "walmart.com" to R48,
        "washingtonpost.com" to R49,
        "wikipedia.org" to R50,
        "wired.com" to R51,
        "x.com" to R40,
        "youtu.be" to R52,
        "youtube.com" to R52
    )
}
