package com.nuvio.tv.core.plugin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSafetyVpnBypassTest {

    @Test
    fun `cb01 and streamingcommunity scrapers bypass the vpn`() {
        assertTrue(PluginSafety.shouldBypassVpn("repo:nuviotv-cb01"))
        assertTrue(PluginSafety.shouldBypassVpn("repo:easystreams-streamingcommunity"))
        assertFalse(PluginSafety.shouldBypassVpn("repo:easystreams-guardoserie"))
    }

    @Test
    fun `streamingcommunity and mixdrop hosts bypass the vpn`() {
        assertTrue(PluginSafety.shouldBypassVpnForUrl("https://vixsrc.to/playlist/1"))
        assertTrue(PluginSafety.shouldBypassVpnForUrl("https://sub.vixcloud.co/embed/1"))
        assertTrue(PluginSafety.shouldBypassVpnForUrl("https://gfve4dog1.mxcontent.net/v2/x.mp4?s=a"))
        assertTrue(PluginSafety.shouldBypassVpnForUrl("https://mixdrop.ag/e/abc"))
        assertTrue(PluginSafety.shouldBypassVpnForUrl("https://m1xdrop.ps/e/abc"))
    }

    @Test
    fun `lookalike and unrelated hosts do not bypass`() {
        assertFalse(PluginSafety.shouldBypassVpnForUrl("https://notvixsrc.to/playlist/1"))
        assertFalse(PluginSafety.shouldBypassVpnForUrl("https://notmixdrop.com/e/abc"))
        assertFalse(PluginSafety.shouldBypassVpnForUrl("https://example.com/mixdrop/x.mp4"))
        assertFalse(PluginSafety.shouldBypassVpnForUrl("https://evil-mxcontent.net/x.mp4"))
    }
}
