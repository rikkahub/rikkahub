package com.termux.terminal;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LocalPtyTransportTest {

    @Test
    public void sessionIsReadAfterCommandName() {
        String stat = "6079 (sleep) S 6065 6079 6050 34816 6079 4194304 98 0 0 0 0 0 0 0 20 0 1 0";
        assertTrue(LocalPtyTransport.isLiveSessionMember(stat, 6050));
        assertFalse(LocalPtyTransport.isLiveSessionMember(stat, 6065));
    }

    @Test
    public void commandNameMayContainSpacesAndParentheses() {
        String stat = "7001 (a) b (c) d) S 1 7001 6050 0 -1 4194560 0 0 0 0";
        assertTrue(LocalPtyTransport.isLiveSessionMember(stat, 6050));
    }

    @Test
    public void zombiesAreNotLive() {
        String stat = "7002 (bash) Z 1 7002 6050 0 -1 4227084 0 0 0 0";
        assertFalse(LocalPtyTransport.isLiveSessionMember(stat, 6050));
    }

    @Test
    public void malformedStatIsIgnored() {
        assertFalse(LocalPtyTransport.isLiveSessionMember("", 1));
        assertFalse(LocalPtyTransport.isLiveSessionMember("1 (init) S 0", 0));
        assertFalse(LocalPtyTransport.isLiveSessionMember("1 (init) S 0 1 x", 0));
    }

}
