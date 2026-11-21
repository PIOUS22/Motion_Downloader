package app.mediadownloader

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Simple built-in ad / tracker blocker for the in-app browser (host list + a few URL patterns + hiding empty ad boxes). */
object AdBlock {
    var enabled by mutableStateOf(true)
        private set

    fun load(ctx: Context) {
        enabled = AppPrefs.p(ctx).getBoolean("adblock", true)
    }

    fun set(ctx: Context, on: Boolean) {
        enabled = on
        AppPrefs.p(ctx).edit().putBoolean("adblock", on).apply()
    }

    private val hosts = hashSetOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com", "2mdn.net",
        "pagead2.googlesyndication.com", "tpc.googlesyndication.com", "google-analytics.com", "adnxs.com", "adsrvr.org",
        "taboola.com", "outbrain.com", "criteo.com", "criteo.net", "pubmatic.com", "rubiconproject.com", "openx.net",
        "casalemedia.com", "advertising.com", "adcolony.com", "moatads.com", "scorecardresearch.com", "quantserve.com",
        "amazon-adsystem.com", "media.net", "revcontent.com", "mgid.com", "popads.net", "popcash.net", "propellerads.com",
        "exoclick.com", "juicyads.com", "trafficjunky.com", "adsterra.com", "hilltopads.net", "clickadu.com",
        "onclickads.net", "adf.ly", "bidvertiser.com", "zedo.com", "yieldmo.com", "smartadserver.com", "adform.net",
        "33across.com", "sharethrough.com", "teads.tv", "ads.yahoo.com", "analytics.yahoo.com", "ads.twitter.com",
        "ads-twitter.com", "adsafeprotected.com", "doubleverify.com", "serving-sys.com", "mathtag.com", "bluekai.com",
        "demdex.net", "krxd.net", "rlcdn.com", "tapad.com", "adroll.com", "inmobi.com", "applovin.com", "mopub.com",
        "startappservice.com", "vungle.com", "chartboost.com", "adtech.com", "adcash.com", "popunder.net", "pushame.com",
        "richpush.co", "notix.io", "pushwoosh.com", "monetag.com", "a-ads.com", "ad.gt", "adskeeper.com", "lijit.com",
        "contextweb.com", "yandex.ru/ads", "mc.yandex.ru", "an.yandex.ru", "ads.tiktok.com", "analytics.tiktok.com",
    )

    private val paths = listOf("/pagead/ads", "/adframe", "/ads/banner", "/adserver/", "/adsbygoogle.js", "/doubleclick/")
    private val safeHosts = listOf("youtube.com", "googlevideo.com", "ytimg.com", "youtu.be")

    fun blocked(u: Uri): Boolean {
        val host = u.host?.lowercase() ?: return false
        if (safeHosts.any { host == it || host.endsWith(".$it") }) return false
        var h = host
        while (true) {
            if (h in hosts) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) break
            h = h.substring(dot + 1)
        }
        val s = u.toString()
        return paths.any { s.contains(it) }
    }

    /** Hides the empty boxes ads leave behind. */
    const val HIDE_JS = "(function(){try{var s=document.getElementById('md-adblock');if(s)return;" +
        "s=document.createElement('style');s.id='md-adblock';" +
        "s.textContent='ins.adsbygoogle,[id^=\"google_ads\"],[id^=\"div-gpt-ad\"],iframe[src*=\"doubleclick\"],iframe[src*=\"googlesyndication\"]," +
        "iframe[id^=\"google_ads\"],.adsbygoogle,.ad-banner,.ad-container,.advert,.advertisement,[aria-label=\"Advertisement\"]," +
        "[data-ad-slot],[data-google-query-id]{display:none!important}';" +
        "(document.head||document.documentElement).appendChild(s)}catch(e){}})()"
}
