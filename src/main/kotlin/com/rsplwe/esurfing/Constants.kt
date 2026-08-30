package com.rsplwe.esurfing

import com.rsplwe.esurfing.utils.randomString

object Constants {

    const val USER_AGENT = "CCTP/android64_vpn/2093"
    // Browser UA used for the captive-portal probe: portal servers pick a
    // different (parameter-less, unusable) redirect target for the client UA,
    // so the probe must present a browser to obtain the real portal URL.
    const val CAPTIVE_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    const val REQUEST_ACCEPT = "text/html,text/xml,application/xhtml+xml,application/x-javascript,*/*"

    // var (not const) so offline MockWebServer tests can redirect the probe.
    var CAPTIVE_URL = "http://connect.rom.miui.com/generate_204"
    const val MAX_REDIRECT_HOPS = 5

    const val PORTAL_END_TAG = "//config.campus.js.chinatelecom.com-->"
    const val PORTAL_START_TAG = "<!--//config.campus.js.chinatelecom.com"
    const val AUTH_KEY = "Eshore!@#"
    val HOST_NAME = randomString(10)
}