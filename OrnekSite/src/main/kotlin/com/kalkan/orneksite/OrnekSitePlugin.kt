package com.kalkan.orneksite

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OrnekSitePlugin : Plugin() {
    override fun load(context: Context) {
        // Bu eklentinin sağlayıcılarını CloudStream'e kaydeder
        registerMainAPI(OrnekSiteProvider())
    }
}
