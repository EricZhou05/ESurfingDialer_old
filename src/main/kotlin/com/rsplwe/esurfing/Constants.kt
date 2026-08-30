package com.rsplwe.esurfing

import com.rsplwe.esurfing.utils.randomString

object Constants {

    const val USER_AGENT = "CCTP/android64_vpn/2093"
    const val REQUEST_ACCEPT = "text/html,text/xml,application/xhtml+xml,application/x-javascript,*/*"

    // var (not const) so offline MockWebServer tests can redirect the probe.
    var CAPTIVE_URL = "http://connect.rom.miui.com/generate_204"
    const val MAX_REDIRECT_HOPS = 5

    const val PORTAL_END_TAG = "//config.campus.js.chinatelecom.com-->"
    const val PORTAL_START_TAG = "<!--//config.campus.js.chinatelecom.com"
    const val AUTH_KEY = "Eshore!@#"
    val HOST_NAME = randomString(10)
}