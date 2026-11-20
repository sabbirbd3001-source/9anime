package com.nineanimes

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class NineAnimesPlugin : BasePlugin() {
    override fun load() {
        // Register the 9animes.cam MainAPI provider
        registerMainAPI(NineAnimesProvider())
    }
}
