package com.smartvision.gallery.lan.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SmbDeviceTest {

    @Test
    fun anonymous_root_url_uses_host_port_share() {
        val device = SmbDevice(host = "192.168.1.100", shareName = "Photos")
        assertEquals("smb://192.168.1.100:445/Photos/", device.toSmbUrl())
        assertEquals("smb://192.168.1.100:445/Photos/", device.rootUrl())
    }

    @Test
    fun anonymous_nested_path_joins_with_slash() {
        val device = SmbDevice(host = "192.168.1.100", shareName = "Photos")
        assertEquals("smb://192.168.1.100:445/Photos/vacation", device.toSmbUrl("vacation"))
        assertEquals("smb://192.168.1.100:445/Photos/vacation/beach.jpg", device.toSmbUrl("vacation/beach.jpg"))
    }

    @Test
    fun leading_slash_path_is_not_doubled() {
        val device = SmbDevice(host = "h", shareName = "s")
        assertEquals("smb://h:445/s/p", device.toSmbUrl("/p"))
    }

    @Test
    fun credentials_embedded_in_url() {
        val device = SmbDevice(
            host = "nas", shareName = "Media", port = 445,
            credentials = SmbCredentials(username = "alice", password = "p@ss:w0rd"),
        )
        assertEquals("smb://alice:p@ss:w0rd@nas:445/Media/", device.toSmbUrl())
    }

    @Test
    fun domain_credentials_prefixed_with_semicolon() {
        val device = SmbDevice(
            host = "nas", shareName = "Media",
            domain = "CORP", credentials = SmbCredentials(username = "bob", password = "pw"),
        )
        assertEquals("smb://CORP;bob:pw@nas:445/Media/", device.toSmbUrl())
    }

    @Test
    fun anonymous_credentials_not_serialized_into_url() {
        val device = SmbDevice(
            host = "nas", shareName = "Media",
            credentials = SmbCredentials(username = "", password = ""),
        )
        assertFalse(device.toSmbUrl().contains("@"))
        assertEquals("smb://nas:445/Media/", device.toSmbUrl())
    }
}
